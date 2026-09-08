//! Environment-owned workers and Promise completion, independent of libuv work.
//!
//! Two workers per environment, at most eight process-wide; 32 admitted calls
//! (including undelivered JS completions), at most four per handle and one
//! executing per handle. Different chains can progress concurrently.
use std::cell::RefCell;
use std::collections::{HashMap, HashSet, VecDeque};
use std::ffi::c_void;
use std::sync::{
    atomic::{AtomicBool, AtomicUsize, Ordering},
    Arc, Condvar, Mutex,
};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use myotis_engine::capi::{myotis_stop, submitted, Submission};
use napi::bindgen_prelude::{FromNapiValue, Object};
use napi::{sys, Env, Error, Result};

const WORKERS: usize = 2;
const MAX_WORKERS: usize = 8;
const CAPACITY: usize = 32;
const PER_HANDLE: usize = 4;
const BUDGET: Duration = Duration::from_secs(90);
static LIVE_WORKERS: AtomicUsize = AtomicUsize::new(0);
thread_local! {
    static ENVS: RefCell<HashMap<usize, Arc<State>>> = RefCell::new(HashMap::new());
    static INITIALIZING: RefCell<HashSet<usize>> = RefCell::new(HashSet::new());
}

type Work = Box<dyn FnOnce() -> String + Send>;
struct Job {
    id: u64,
    handle: i64,
    deferred: usize,
    submission: Submission,
    run: Work,
}
struct Completion {
    deferred: usize,
    value: String,
}
struct Inner {
    // Used only under this mutex; cleared BEFORE cleanup cancels/joins workers.
    tsfn: Option<usize>,
    closing: bool,
    cleanup_started: bool,
    poisoned: bool,
    fallback_error: Option<usize>,
    queue: VecDeque<Job>,
    active: HashSet<i64>,
    requests: HashMap<u64, (i64, Arc<AtomicBool>)>,
    handles: HashSet<i64>,
    pending: usize,
    next_id: u64,
}
struct State {
    inner: Mutex<Inner>,
    changed: Condvar,
    workers: Mutex<Vec<JoinHandle<()>>>,
}

fn check(status: sys::napi_status) -> Result<()> {
    if status == sys::Status::napi_ok {
        Ok(())
    } else {
        Err(Error::from_reason(format!(
            "Node-API scheduler error: {status}"
        )))
    }
}

unsafe extern "C" fn complete(
    env: sys::napi_env,
    _callback: sys::napi_value,
    context: *mut c_void,
    data: *mut c_void,
) {
    if data.is_null() {
        return;
    }
    let completion = unsafe { Box::from_raw(data.cast::<Completion>()) };
    let state = unsafe { &*context.cast::<Arc<State>>() };
    // Node drains aborted TSFN items with null env. Account the item but never
    // call Node-API; the TSFN context remains owned until its finalizer.
    if env.is_null()
        || state
            .inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .closing
    {
        delivered(state, std::ptr::null_mut());
        return;
    }
    let fallback = state
        .inner
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .fallback_error;
    let mut error = std::ptr::null_mut();
    let got_error = fallback
        .map(|fallback| unsafe {
            sys::napi_get_reference_value(env, fallback as sys::napi_ref, &mut error)
        })
        .unwrap_or(sys::Status::napi_invalid_arg);
    if got_error != sys::Status::napi_ok || error.is_null() {
        // Closing/pending exceptions can prevent even the preallocated error
        // lookup. No settlement is attempted and no JS-side fatal_error is used.
        poison(state);
        delivered(state, env);
        eprintln!("Myotis: completion error reference unavailable; scheduler poisoned");
        let mut pending = false;
        unsafe {
            sys::napi_is_exception_pending(env, &mut pending);
        }
        if !pending && !error.is_null() {
            unsafe {
                sys::napi_fatal_exception(env, error);
            }
        }
        return;
    }
    let mut value = std::ptr::null_mut();
    let created = unsafe {
        sys::napi_create_string_utf8(
            env,
            completion.value.as_ptr().cast(),
            completion.value.len() as isize,
            &mut value,
        )
    };
    // Choose success/error BEFORE touching the deferred. Node's ConcludeDeferred
    // deletes its deferred_ref even if Resolve/Reject returns generic_failure:
    // https://github.com/nodejs/node/blob/v24.17.0/src/js_native_api_v8.cc#L309
    // Never retry settlement or inspect that pointer after this one attempt.
    let settled = if created == sys::Status::napi_ok {
        unsafe { sys::napi_resolve_deferred(env, completion.deferred as sys::napi_deferred, value) }
    } else {
        let mut pending = false;
        unsafe {
            sys::napi_is_exception_pending(env, &mut pending);
        }
        if pending {
            let mut exception = std::ptr::null_mut();
            unsafe {
                sys::napi_get_and_clear_last_exception(env, &mut exception);
            }
            if !exception.is_null() {
                error = exception;
            }
        }
        unsafe { sys::napi_reject_deferred(env, completion.deferred as sys::napi_deferred, error) }
    };
    if settled != sys::Status::napi_ok {
        poison(state);
    }
    delivered(state, env);
    if settled != sys::Status::napi_ok {
        // The deferred is consumed/unknown. Report after releasing accounting
        // and locks: uncaughtException may run user JS and is NOT guaranteed to
        // terminate Node. This is explicitly a failed completion, never success.
        eprintln!("Myotis: Node-API Promise settlement failed ({settled}); scheduler poisoned; deferred not retried");
        unsafe {
            sys::napi_fatal_exception(env, error);
        }
    }
}

fn poison(state: &State) {
    let mut inner = state.inner.lock().unwrap_or_else(|e| e.into_inner());
    inner.poisoned = true;
    for (_, token) in inner.requests.values() {
        token.store(true, Ordering::Release);
    }
}

fn delivered(state: &State, env: sys::napi_env) {
    let mut inner = state.inner.lock().unwrap_or_else(|e| e.into_inner());
    inner.pending -= 1;
    if inner.pending == 0 && !env.is_null() && !inner.closing {
        if let Some(tsfn) = inner.tsfn {
            let status = unsafe {
                sys::napi_unref_threadsafe_function(env, tsfn as sys::napi_threadsafe_function)
            };
            if status != sys::Status::napi_ok {
                inner.poisoned = true;
                eprintln!("Myotis: completion unref failed ({status}); scheduler poisoned");
            }
        }
    }
}

unsafe extern "C" fn finalize(_env: sys::napi_env, data: *mut c_void, _hint: *mut c_void) {
    unsafe {
        drop(Box::from_raw(data.cast::<Arc<State>>()));
    }
}

impl State {
    fn finish(&self, job: Job, value: String) {
        let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        inner.requests.remove(&job.id);
        inner.active.remove(&job.handle);
        if let Some(tsfn) = inner.tsfn {
            let completion = Box::into_raw(Box::new(Completion {
                deferred: job.deferred,
                value,
            }));
            // Nonblocking: a worker NEVER needs the JS thread to make progress.
            // Admission bounds this otherwise-unbounded TSFN queue to CAPACITY.
            let status = unsafe {
                sys::napi_call_threadsafe_function(
                    tsfn as sys::napi_threadsafe_function,
                    completion.cast(),
                    sys::ThreadsafeFunctionCallMode::nonblocking,
                )
            };
            if status != sys::Status::napi_ok {
                unsafe {
                    drop(Box::from_raw(completion));
                }
                if status == sys::Status::napi_closing {
                    // No more Node-API operations on a closing TSFN. The env
                    // cleanup hook owns worker joins and remaining bookkeeping.
                    inner.tsfn = None;
                    inner.closing = true;
                    inner.pending -= 1;
                    for (_, token) in inner.requests.values() {
                        token.store(true, Ordering::Release);
                    }
                } else {
                    // Queue-full is impossible (max_queue_size=0); invalid-arg
                    // indicates a broken lifetime invariant. There is no safe
                    // JS-thread delivery path through an invalid TSFN.
                    fatal_completion();
                }
            }
        } else {
            inner.pending -= 1;
        }
        self.changed.notify_all();
    }

    fn worker(self: Arc<Self>) {
        struct Slot;
        impl Drop for Slot {
            fn drop(&mut self) {
                LIVE_WORKERS.fetch_sub(1, Ordering::AcqRel);
            }
        }
        let _slot = Slot;
        loop {
            let mut job = {
                let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
                loop {
                    if inner.closing {
                        return;
                    }
                    if let Some(index) = inner
                        .queue
                        .iter()
                        .position(|j| !inner.active.contains(&j.handle))
                    {
                        let Some(job) = inner.queue.remove(index) else {
                            continue;
                        };
                        inner.active.insert(job.handle);
                        break job;
                    }
                    inner = self.changed.wait(inner).unwrap_or_else(|e| e.into_inner());
                }
            };
            let value = if job.submission.cancelled.load(Ordering::Acquire) {
                r#"{"error":"request cancelled"}"#.to_string()
            } else if Instant::now() >= job.submission.deadline {
                r#"{"error":"request deadline exceeded in queue"}"#.to_string()
            } else {
                let run = std::mem::replace(&mut job.run, Box::new(String::new));
                submitted(job.submission.clone(), run)
            };
            self.finish(job, value);
        }
    }

    fn cleanup(&self, env: sys::napi_env) {
        let handles = {
            let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
            if inner.cleanup_started {
                return;
            }
            inner.cleanup_started = true;
            inner.closing = true;
            // Serialize invalidation with worker completion. No worker can call
            // Node-API once the TSFN is removed, including after this hook returns.
            if let Some(tsfn) = inner.tsfn.take() {
                unsafe {
                    sys::napi_release_threadsafe_function(
                        tsfn as sys::napi_threadsafe_function,
                        sys::ThreadsafeFunctionReleaseMode::abort,
                    );
                }
            }
            for (_, cancelled) in inner.requests.values() {
                cancelled.store(true, Ordering::Release);
            }
            while let Some(job) = inner.queue.pop_front() {
                inner.requests.remove(&job.id);
                inner.pending -= 1;
            }
            if let Some(error) = inner.fallback_error.take() {
                unsafe {
                    sys::napi_delete_reference(env, error as sys::napi_ref);
                }
            }
            inner.handles.drain().collect::<Vec<_>>()
        };
        self.changed.notify_all();
        let workers = std::mem::take(&mut *self.workers.lock().unwrap_or_else(|e| e.into_inner()));
        for worker in workers {
            let _ = worker.join();
        }
        // Workers have actually exited; no callback delivery is needed to join.
        // Native teardown still includes non-preemptible filesystem/CL work.
        for handle in handles {
            myotis_stop(handle);
        }
        ENVS.with(|envs| {
            envs.borrow_mut().remove(&(env as usize));
        });
    }
}

fn state(env: &Env) -> Result<Arc<State>> {
    let key = env.raw() as usize;
    if let Some(state) = ENVS.with(|envs| envs.borrow().get(&key).cloned()) {
        return Ok(state);
    }
    // TSFN creation may invoke async_hooks synchronously before ENVS can
    // publish a fully initialized state. Refuse recursive initialization rather
    // than create a second owner whose handles the outer insertion would lose.
    if !INITIALIZING.with(|initializing| initializing.borrow_mut().insert(key)) {
        return Err(Error::from_reason("Myotis scheduler initializing"));
    }
    struct Initializing(usize);
    impl Drop for Initializing {
        fn drop(&mut self) {
            INITIALIZING.with(|initializing| {
                initializing.borrow_mut().remove(&self.0);
            });
        }
    }
    let _initializing = Initializing(key);
    LIVE_WORKERS
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |n| {
            (n + WORKERS <= MAX_WORKERS).then_some(n + WORKERS)
        })
        .map_err(|_| Error::from_reason("Myotis environment worker limit reached"))?;
    let state = Arc::new(State {
        inner: Mutex::new(Inner {
            tsfn: None,
            closing: false,
            cleanup_started: false,
            poisoned: false,
            fallback_error: None,
            queue: VecDeque::new(),
            active: HashSet::new(),
            requests: HashMap::new(),
            handles: HashSet::new(),
            pending: 0,
            next_id: 1,
        }),
        changed: Condvar::new(),
        workers: Mutex::new(Vec::new()),
    });
    let setup = (|| {
        let mut message = std::ptr::null_mut();
        let mut error = std::ptr::null_mut();
        let mut error_ref = std::ptr::null_mut();
        check(unsafe {
            sys::napi_create_string_utf8(
                env.raw(),
                c"Myotis Promise completion failed".as_ptr(),
                32,
                &mut message,
            )
        })?;
        check(unsafe {
            sys::napi_create_error(env.raw(), std::ptr::null_mut(), message, &mut error)
        })?;
        check(unsafe { sys::napi_create_reference(env.raw(), error, 1, &mut error_ref) })?;
        state
            .inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .fallback_error = Some(error_ref as usize);
        let mut resource_name = std::ptr::null_mut();
        check(unsafe {
            sys::napi_create_string_utf8(
                env.raw(),
                c"myotis-completion".as_ptr(),
                17,
                &mut resource_name,
            )
        })?;
        let context = Box::into_raw(Box::new(Arc::clone(&state)));
        let mut tsfn = std::ptr::null_mut();
        let status = unsafe {
            sys::napi_create_threadsafe_function(
                env.raw(),
                std::ptr::null_mut(),
                std::ptr::null_mut(),
                resource_name,
                0,
                1,
                context.cast(),
                Some(finalize),
                context.cast(),
                Some(complete),
                &mut tsfn,
            )
        };
        if status != sys::Status::napi_ok {
            unsafe {
                drop(Box::from_raw(context));
            }
            check(status)?;
        }
        state.inner.lock().unwrap_or_else(|e| e.into_inner()).tsfn = Some(tsfn as usize);
        // Idle addon does not keep Node alive. Ref once on first admission and
        // unref after the last JS completion; no premature normal process exit.
        check(unsafe { sys::napi_unref_threadsafe_function(env.raw(), tsfn) })?;
        let cleanup = Arc::clone(&state);
        // Registered after TSFN creation: LIFO cleanup invalidates our producer
        // before Node's own TSFN teardown hook can run.
        env.add_env_cleanup_hook(key, move |key| cleanup.cleanup(key as sys::napi_env))?;
        Ok(())
    })();
    if let Err(error) = setup {
        if let Some(tsfn) = state
            .inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .tsfn
            .take()
        {
            unsafe {
                sys::napi_release_threadsafe_function(
                    tsfn as sys::napi_threadsafe_function,
                    sys::ThreadsafeFunctionReleaseMode::abort,
                );
            }
        }
        if let Some(reference) = state
            .inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .fallback_error
            .take()
        {
            unsafe {
                sys::napi_delete_reference(env.raw(), reference as sys::napi_ref);
            }
        }
        LIVE_WORKERS.fetch_sub(WORKERS, Ordering::AcqRel);
        return Err(error);
    }
    for index in 0..WORKERS {
        let worker_state = Arc::clone(&state);
        match std::thread::Builder::new()
            .name(format!("myotis-node-{index}"))
            .spawn(move || worker_state.worker())
        {
            Ok(worker) => state
                .workers
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .push(worker),
            Err(error) => {
                LIVE_WORKERS.fetch_sub(WORKERS - index, Ordering::AcqRel);
                state.cleanup(env.raw());
                return Err(Error::from_reason(format!(
                    "Myotis worker creation failed: {error}"
                )));
            }
        }
    }
    ENVS.with(|envs| {
        envs.borrow_mut().insert(key, Arc::clone(&state));
    });
    Ok(state)
}

pub fn created(env: &Env, handle: i64) -> Result<()> {
    state(env)?
        .inner
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .handles
        .insert(handle);
    Ok(())
}

pub fn prepare(env: &Env) -> Result<()> {
    state(env).map(|_| ())
}

/// Cancel and drain native jobs before pause/stop can publish a new generation.
/// This waits for native completion, never JS completion. The caller is the
/// environment thread, so another submission cannot race this lifecycle call.
pub fn cancel_handle(env: &Env, handle: i64, remove: bool) -> Result<bool> {
    let state = state(env)?;
    let mut inner = state.inner.lock().unwrap_or_else(|e| e.into_inner());
    if !inner.handles.contains(&handle) {
        return Ok(false);
    }
    for (h, token) in inner.requests.values() {
        if *h == handle {
            token.store(true, Ordering::Release);
        }
    }
    state.changed.notify_all();
    while inner.requests.values().any(|(h, _)| *h == handle) {
        inner = state.changed.wait(inner).unwrap_or_else(|e| e.into_inner());
    }
    if remove {
        inner.handles.remove(&handle);
    }
    Ok(true)
}

pub fn submit<'env>(
    env: &'env Env,
    handle: i64,
    run: impl FnOnce() -> String + Send + 'static,
) -> Result<Object<'env>> {
    let deadline = Instant::now() + BUDGET;
    let state = state(env)?;
    let mut deferred = std::ptr::null_mut();
    let mut promise = std::ptr::null_mut();
    // Promise hooks may synchronously reenter the addon. Never hold our lock
    // while creating or settling a Promise.
    check(unsafe { sys::napi_create_promise(env.raw(), &mut deferred, &mut promise) })?;
    let mut inner = state.inner.lock().unwrap_or_else(|e| e.into_inner());
    let error = if !inner.handles.contains(&handle) {
        Some("handle does not belong to this environment")
    } else if inner.closing {
        Some("environment closing")
    } else if inner.poisoned {
        Some("native scheduler poisoned")
    } else if inner.pending >= CAPACITY
        || inner
            .requests
            .values()
            .filter(|(h, _)| *h == handle)
            .count()
            >= PER_HANDLE
    {
        Some("native scheduler busy")
    } else {
        None
    };
    if let Some(error) = error {
        drop(inner);
        let json = format!(r#"{{"error":"{error}"}}"#);
        let mut value = std::ptr::null_mut();
        check(unsafe {
            sys::napi_create_string_utf8(
                env.raw(),
                json.as_ptr().cast(),
                json.len() as isize,
                &mut value,
            )
        })?;
        check(unsafe { sys::napi_resolve_deferred(env.raw(), deferred, value) })?;
    } else {
        let next_id = inner
            .next_id
            .checked_add(1)
            .ok_or_else(|| Error::from_reason("request id exhausted"))?;
        if inner.pending == 0 {
            if let Some(tsfn) = inner.tsfn {
                check(unsafe {
                    sys::napi_ref_threadsafe_function(
                        env.raw(),
                        tsfn as sys::napi_threadsafe_function,
                    )
                })?;
            }
        }
        let id = inner.next_id;
        inner.next_id = next_id;
        let cancelled = Arc::new(AtomicBool::new(false));
        inner.pending += 1;
        inner.requests.insert(id, (handle, Arc::clone(&cancelled)));
        inner.queue.push_back(Job {
            id,
            handle,
            deferred: deferred as usize,
            submission: Submission {
                deadline,
                cancelled,
            },
            run: Box::new(run),
        });
        state.changed.notify_all();
    }
    unsafe { Object::from_napi_value(env.raw(), promise) }
}

pub fn owns(env: &Env, handle: i64) -> bool {
    ENVS.with(|envs| {
        envs.borrow()
            .get(&(env.raw() as usize))
            .is_some_and(|state| {
                state
                    .inner
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .handles
                    .contains(&handle)
            })
    })
}

/// Node-API invariant failure, not an engine/query failure. Never leave a live
/// environment with an orphaned promise and a permanent TSFN reference.
fn fatal_completion() -> ! {
    unsafe {
        sys::napi_fatal_error(
            c"myotis".as_ptr(),
            6,
            c"unrecoverable Node-API completion failure".as_ptr(),
            41,
        )
    }
    // Node documents fatal_error as non-returning; the FFI signature is void.
    std::process::abort()
}
