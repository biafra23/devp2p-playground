# myotis-node

Node.js binding over the myotis-engine **C ABI** (`capi.rs` /
`rust/include/myotis_engine.h`) via [napi-rs](https://napi.rs) — the seam for
Electron/desktop hosts that want to run Myotis invisibly in-process, the way
they run other embedded nodes.

This is the third consumer of the same ABI seam, next to the hand-JNI surface
(JVM hosts) and the Kotlin/Native cinterop (iOS): identical JSON shapes (pinned
by the cross-engine golden tests), identical in-band error sentinels (negative
handle ids, `false`, `{"error": ...}` objects — no JS exceptions for
engine-level failures).

## Build

```bash
cargo build -p myotis-node --release
cp ../target/release/libmyotis_node.so myotis-node.node   # .dylib on macOS, .dll on Windows
```

## Use

```js
const myotis = require('./myotis-node.node');

myotis.init();   // ABI handshake — returns the engine ABI version; gate on
                 // the value pinned in the notes of the release you built or
                 // downloaded against
const h = myotis.create('mainnet', '/path/to/data-dir');  // dir is created if missing
myotis.start(h);

// Lifecycle and status are synchronous (stop/pause can wait for native work):
JSON.parse(myotis.statusJson(h));   // { beaconState, peerCount, snapPeers, ... }

// Verified reads run on bounded Myotis workers, with a 90 s operation budget
// including queue wait. Cancellation drains native work before completion:
const acct = JSON.parse(await myotis.requestAccountJson(h, '0xd8dA…6045'));
const ens = JSON.parse(await myotis.resolveEnsJson(h, 'vitalik.eth'));
const ch = JSON.parse(await myotis.ensRecordJson(h, JSON.stringify({
  method: 'contenthash', name: 'vitalik.eth',
})));

myotis.pause(h);   // idle-sleep: tear down networking, keep warm state
myotis.resume(h);  // warm restart
myotis.stop(h);
```

`smoke.mjs` is the end-to-end check: syncs mainnet from plain Node, then runs
`resolve-ens` + `contenthash` + `get-account` with verification fields and
cold/warm timing:

```bash
node smoke.mjs ./data-dir ../target/debug/myotis-node.node
```

It begins the reads only once the peer set is worth judging — `snapPeers >= 2`
(the reader rotates, so one peer means one dud peer looks like a broken
engine) and discovery has produced candidates. Exit codes distinguish the two
verdicts that used to be one: **0** all checks passed, **1** the engine
answered and a check failed (or it never became ready), **2** the environment
never produced a usable peer set. Knobs for constrained runners:
`MYOTIS_SMOKE_MIN_SNAP_PEERS`, `MYOTIS_SMOKE_REQUIRE_DISCOVERY`,
`MYOTIS_SMOKE_GATE_TIMEOUT_MIN` (how long before a PEER-STARVED gate gives up
early — engine-side shortfalls always get the full budget, because a cold
checkpoint catch-up legitimately takes 30-40 min) and `MYOTIS_SMOKE_TIMEOUT_MIN`
(the overall budget). A set-but-nonsense value for any of them is refused at
startup rather than silently ignored. The gate itself is
unit-tested in `smoke-gate.test.mjs` (`node --test smoke-gate.test.mjs`).

## Notes

- **Readiness**: serve verified reads only when `statusJson` shows
  `beaconState === 'SYNCED'` and `elReaderAvailable`; before that, reads
  honestly error rather than guess. `snapPeers > 0` is the minimum to answer
  at all, but a host that wants a read to SURVIVE one silent peer should wait
  for `snapPeers >= 2` — the reader rotates over the snap set, and with a
  single peer there is nowhere to rotate to (this is what `smoke.mjs` gates
  on; see #372).
- **Weak-subjectivity gate**: `statusJson().beaconState` can be `STALE_ANCHOR`
  — the engine refused to walk forward from an anchor (embedded checkpoint or
  persisted snapshot) older than the network's WS bound, because from that far
  back a forged continuation is BLS-indistinguishable from the honest chain
  (a long-range attack). While parked, verified reads fail closed and
  `statusJson().wsBoundPeriods` reports the effective bound. The bound is small
  on some networks (~34 h on gnosis, vs. ~2 weeks on mainnet), so an embedding
  must decide how a parked node behaves. Two host-owned controls (neither is
  persisted by the engine): `setWsBoundPeriods(h, periods)` raises the accepted
  anchor age (`0` restores the network default), applied live; and
  `acceptStaleAnchor(h)` gives run-sticky consent to sync past the park for the
  rest of this run. Put them behind your own UI or set a policy on your users'
  behalf — the durable alternative on short-window chains is a fresher anchor
  (ship/refresh the checkpoint), not a wider gate.
- **data_dir**: the engine creates it on `create()` (an uncreatable path
  yields a negative handle) as of the data_dir fix; on engine versions
  without it, create the directory yourself first — otherwise sync works but
  snapshot writes fail with ENOENT and every restart is a cold start.
- **CCIP-Read (`status: "offchain"`)**: the engine returns the gateway tuple;
  driving the HTTP round and re-entering via `method: "ccipCallback"` is the
  host's job (not yet wrapped here).
- The addon is loadable from Electron main/utility processes as-is (N-API is
  ABI-stable across Node and Electron).

## Request ownership and cancellation

This implementation preserves the current engine's **ABI 25** and existing JS
argument/result shapes. It is not a drop-in artifact for a host pinned to ABI 22.
Engine failures, admission refusal, cancellation, and deadline expiry remain
in-band JSON errors. Node-API infrastructure failures may throw/reject.

Each Node environment owns two native workers, with a process-wide ceiling of
eight workers. Admission is capped at 32 requests including completions awaiting
JS delivery, with at most four queued/executing requests per handle and one
executing request per handle. Two chains can execute concurrently. Saturation
fails immediately with `{"error":"native scheduler busy"}`; there is no unbounded
thread creation or libuv work item. A fifth concurrent environment fails to initialize at the process worker cap.
Handles belong to the environment that created them and cannot be transferred to another Node worker environment.

A 90-second budget starts at submission, before scheduler setup and queue wait.
Expired or cancelled queued jobs never call the engine. The same deadline and
cancellation bit cross the C seam into async reader setup/network waits, EVM
oracle waits (including writer locks and sends), and EVM instruction checks.
ENS attempts use a shorter child budget and drain before AUTO changes roots.
Proof validation and cache trust rules are unchanged. An indivisible proof,
precompile, filesystem call, or OS operation can overrun the cooperative budget.

`stop` and `pause` remain **synchronous**. They cancel and drain native jobs before
teardown or a new reader generation; Promise delivery follows when JS can run.
The shared engine signals readers even while other `Arc`s own them and drains
registered read/execution work. Started EVM closures retain their global permits
(maximum eight) and accounting until they actually return. Pool shutdown joins
owned parent loops and spawned dial/backfill/send work before closing peers.
Pause/resume/start/stop on a handle must be serialized by C/JNI/UniFFI callers.

Environment cleanup closes admission and the completion producer, cancels queued
and active work, joins native workers without waiting for JS callbacks, and stops
owned handles. It never drops the shared Tokio runtime. The completion TSFN is
referenced while requests await delivery and unreferenced when idle. Cleanup
cannot preempt indivisible work: **this is not a hard-stop or crash-isolation
contract**. Hosts needing a hard shutdown deadline still need a supervised
process boundary. Cancelled/timed-out transaction gossip does not prove that a
transaction was not broadcast; do not blindly retry a signed submission.

Qualification of Node/Electron cleanup, forced environment teardown, parent DNS
liveness, queue cancellation, cross-chain fairness, and native overruns belongs
on disposable hosts. Exact-lock Node build/runtime qualification is required
before releasing a new artifact; a type-check with cached dependency patch
versions does not qualify that artifact.

The completion bridge makes exactly one resolve/reject attempt per deferred.
A result-string allocation failure selects rejection before settlement. If
Node's settlement itself fails, deferred ownership is consumed/unknown: the
addon releases its admission/keepalive accounting, marks the scheduler poisoned,
cancels remaining native work, and reports an uncaught exception plus a stderr
diagnostic, without retrying that pointer. New reads resolve with
`{"error":"native scheduler poisoned"}`; lifecycle cleanup remains available. A host
`uncaughtException` handler can suppress termination, so this is not a guarantee
that the failed Promise settles. If even the preallocated error reference cannot be obtained, no settlement is
attempted: the scheduler is poisoned and the pending exception or stderr
reports the loss. JS-side allocation and pending-exception errors never use
`napi_fatal_error`. A proven live worker-side TSFN enqueue invariant
failure is process-fatal (including in standalone Node); ordinary engine errors
and environment closing do not use that path.

Queued stop/pause cancellations are removed immediately under the queue lock
and delivered through the TSFN; they do not wait for workers occupied by other
chains, and they never release the slot of an actually executing sibling.
Stop/pause wait only for the target handle's active native job. Poisoning also
cancels queued jobs directly. An impossible owner-thread TSFN enqueue failure
retains the completion for one JS-thread settlement after native lifecycle
returns; ordinary lifecycle cancellation does not run synchronous Promise hooks.

Initialization/read calls from a Node `async_hooks` init hook during scheduler
creation are unsupported and throw. An uncaught exception inside that hook can
terminate Node; the initialization guard prevents a second scheduler from
replacing the first owner's state. Ownership-gated status/lifecycle calls in
that window return their unavailable sentinels. Synchronous `create`, `pause`
and `stop` may also throw scheduler/Node-API infrastructure errors; engine-level
read failures still use JSON error results.
