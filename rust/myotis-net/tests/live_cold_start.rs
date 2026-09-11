//! The two cold-start regressions issue #422 asked us to keep.
//!
//! Both are live-network tests, ignored by default. `SyncHandle::start` is
//! already a cold start — the `ChainConfig` constructors set `snapshot_path:
//! None`, so there is no sync snapshot and no CL peer cache — which is exactly
//! the profile a fresh install has and the one a warm dispatched smoke run
//! hides. Each test then removes ONE crutch and asserts the wallet still
//! reaches SYNCED:
//!
//! * `cold_start_without_static_peers_syncs_through_discovery` — every pinned
//!   server is gone, so discovery alone has to find light-client servers.
//!   This is the "recovery when the preferred static endpoint is unavailable"
//!   case: in the reported incident the pins were stale AND roost was down, and
//!   nothing else got the wallet a usable peer.
//!
//! * `cold_start_from_an_old_anchor_walks_periods_to_head` — the trust anchor
//!   is several periods behind, so bootstrap is not enough and catch-up must
//!   actually walk. A release-fresh anchor makes the walk 0–2 periods and
//!   proves almost nothing, which is why the anchor is a parameter.
//!
//! ```bash
//! # discovery-only (default network: gnosis — where #422 bit hardest)
//! cargo test -p myotis-net --test live_cold_start -- --ignored --nocapture \
//!     cold_start_without_static_peers
//!
//! # Old anchor: it must be MORE THAN the network's weak-subjectivity bound
//! # behind the wall clock (gnosis 3 periods, mainnet/sepolia 13) — the test
//! # asserts that, because a shallower anchor walks a period or two and would
//! # have survived the bug this guards.
//! #
//! # Take it from a release OLDER than the current one. Note the newest tag is
//! # usually the release whose anchor is the one on main, so it is no good:
//! #   prev=$(git tag --sort=-v:refname | sed -n 2p)
//! #   git show "$prev":rust/myotis-net/src/sync.rs | grep -A6 '@checkpoint:gnosis:begin'
//! #
//! # Worked example, measured 2026-09-11 — v0.1.7's gnosis anchor, 70 periods
//! # behind, walked to head in 170 s:
//! #   MYOTIS_TEST_ANCHOR_ROOT=5387a11e014d8d4a9e8ca072ccd6639be912ab9a15b14b3b1f2d49b79551d954
//! #   MYOTIS_TEST_ANCHOR_SLOT=29458656
//! NET=gnosis \
//! MYOTIS_TEST_ANCHOR_ROOT=<64 hex> MYOTIS_TEST_ANCHOR_SLOT=<slot> \
//! cargo test -p myotis-net --test live_cold_start -- --ignored --nocapture \
//!     cold_start_from_an_old_anchor
//! ```
//!
//! NOTE both tests are peer-quota-bound, not CPU-bound: light-client servers
//! serve roughly one update per 10 s each, so a deep walk takes minutes. The
//! budgets below are generous for that reason, and a failure means "no peer
//! would serve us in N minutes", which is the condition #422 reported.

use std::sync::atomic::Ordering;
use std::time::Duration;

use myotis_net::{ChainConfig, SyncHandle, SyncState};

/// Network under test. Gnosis when unset: its light-client servers are almost
/// all Lighthouse, which is the population that stopped answering in #422.
/// An unrecognised value REFUSES rather than falling back — a cold-start run
/// against a network the operator did not ask for is a green result that means
/// something else (CLAUDE.md: a parameter that can change the answer must be
/// applied or refused, never accepted and silently ignored).
fn config_for_env() -> ChainConfig {
    match std::env::var("NET").unwrap_or_else(|_| "gnosis".into()).as_str() {
        "mainnet" => ChainConfig::mainnet(),
        "sepolia" => ChainConfig::sepolia(),
        "gnosis" => ChainConfig::gnosis(),
        other => panic!("unknown NET {other:?} (want mainnet, sepolia or gnosis)"),
    }
}

/// How long the discovery-only cold start may take. Named, with the prose
/// derived from it, so the budget and the message reporting it cannot drift
/// apart — the bug this PR fixes in `examples/live_sync.rs`.
const NO_PINS_BUDGET: Duration = Duration::from_secs(900);

/// How long the deep catch-up may take. Peer-quota-bound: a serving peer
/// answers roughly one update per 10 s, so a 70-period walk is minutes.
const OLD_ANCHOR_BUDGET: Duration = Duration::from_secs(1800);

fn init_tracing() {
    let _ = tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info,myotis_net=debug".into()),
        )
        .try_init();
}

/// Drive the handle to SYNCED or the deadline.
async fn run_to_synced(
    handle: &SyncHandle,
    label: &str,
    budget: Duration,
) -> Option<myotis_net::SyncStatus> {
    let deadline = tokio::time::Instant::now() + budget;
    while tokio::time::Instant::now() < deadline {
        tokio::time::sleep(Duration::from_secs(5)).await;
        let s = handle.status();
        eprintln!(
            "[{label}] state={} period={} start_period={} finalized_slot={} peers={} served/min={}",
            s.state, s.period, s.sync_start_period, s.finalized_slot, s.peer_count,
            s.served_peers_last_min
        );
        if s.state == SyncState::Synced {
            return Some(s);
        }
    }
    None
}

/// The CL source-selection overrides are applied in the `ChainConfig`
/// constructors, so a stray one silently changes what a test dials — setting
/// `MYOTIS_CL_STATIC_PEERS` to a known-good server is exactly how #422's
/// reporter built their *control*, which is the opposite of what these
/// regressions measure.
fn assert_no_cl_env_overrides() {
    for var in ["MYOTIS_CL_STATIC_PEERS", "MYOTIS_CL_DISABLE_DISCV5"] {
        assert!(
            std::env::var_os(var).is_none(),
            "{var} is set — it changes which peers this test dials, so the result \
             would not mean what the test claims. Unset it."
        );
    }
}

#[tokio::test(flavor = "multi_thread")]
#[ignore = "live network test: cold start with NO pinned peers, takes minutes"]
async fn cold_start_without_static_peers_syncs_through_discovery() {
    init_tracing();
    assert_no_cl_env_overrides();
    let mut config = config_for_env();
    let pinned = config.static_peers.len();
    // The crutch under test. Discovery keeps its bootnodes — removing those
    // would test nothing but "a node with no way in cannot get in".
    config.static_peers.clear();
    assert!(!config.bootstrap_enrs.is_empty(), "discovery needs its bootnodes");

    let handle = SyncHandle::start(config).expect("sync start");
    let synced = run_to_synced(&handle, "no-pins", NO_PINS_BUDGET).await;
    handle.stop().await;

    assert!(
        synced.is_some(),
        "cold start with all {pinned} pinned peers removed did not reach SYNCED in {} min — \
         discovery alone could not find a light-client server, which is the #422 condition",
        NO_PINS_BUDGET.as_secs() / 60
    );
}

#[tokio::test(flavor = "multi_thread")]
#[ignore = "live network test: cold start from an OLD anchor, takes many minutes"]
async fn cold_start_from_an_old_anchor_walks_periods_to_head() {
    init_tracing();
    assert_no_cl_env_overrides();
    let mut config = config_for_env();

    // The anchor is required, and a missing one FAILS rather than returning:
    // this test only runs when someone asked for it by name (it is #[ignore]d),
    // and a green "1 passed" for a run that did nothing is how a regression
    // quietly stops being one.
    let env_non_empty = |k: &str| std::env::var(k).ok().filter(|v| !v.trim().is_empty());
    let root_hex = env_non_empty("MYOTIS_TEST_ANCHOR_ROOT").unwrap_or_else(|| {
        panic!(
            "set MYOTIS_TEST_ANCHOR_ROOT + MYOTIS_TEST_ANCHOR_SLOT to an anchor further \
             behind than this network's weak-subjectivity bound — see this file's header \
             for where to get one"
        )
    });
    let slot: u64 = env_non_empty("MYOTIS_TEST_ANCHOR_SLOT")
        .expect("MYOTIS_TEST_ANCHOR_SLOT must accompany MYOTIS_TEST_ANCHOR_ROOT")
        .trim()
        .parse()
        .expect("anchor slot must be a number");
    let root_hex = root_hex.trim().trim_start_matches("0x");
    assert_eq!(root_hex.len(), 64, "anchor root must be 32 bytes of hex");
    let mut root = [0u8; 32];
    for (i, b) in root.iter_mut().enumerate() {
        *b = u8::from_str_radix(&root_hex[i * 2..i * 2 + 2], 16).expect("anchor root hex");
    }

    let anchor_period = slot / config.slots_per_period();
    let wall_period = config.wall_clock_period();
    let bound = config.effective_ws_bound_periods();
    assert!(
        wall_period > anchor_period && wall_period - anchor_period > bound,
        "anchor period {anchor_period} is only {} behind the wall clock ({wall_period}), \
         which is within this network's weak-subjectivity bound of {bound} periods — a walk \
         that short would have survived the #422 bug, so the test would prove nothing",
        wall_period.saturating_sub(anchor_period)
    );
    let behind = wall_period - anchor_period;
    eprintln!("[old-anchor] anchor period {anchor_period}, wall {wall_period} ({behind} behind, \
               ws bound {bound})");

    config.checkpoint_root = root;
    config.checkpoint_slot = slot;
    // An anchor this old is past the weak-subjectivity bound, so the engine
    // parks in STALE_ANCHOR until a host consents. Consent here: the point of
    // the test is the catch-up walk behind that gate, and the gate itself has
    // its own coverage.
    config.ws_policy.accept_stale_anchor.store(true, Ordering::Relaxed);

    let handle = SyncHandle::start(config).expect("sync start");
    let synced = run_to_synced(&handle, "old-anchor", OLD_ANCHOR_BUDGET).await;
    handle.stop().await;

    let synced = synced.unwrap_or_else(|| {
        panic!(
            "cold start {behind} periods behind did not reach SYNCED in {} min — \
             the bootstrap may have landed but catch-up made no progress, which is \
             exactly the #422 stall",
            OLD_ANCHOR_BUDGET.as_secs() / 60
        )
    });
    // `sync_start_period` is the period this run's catch-up started from (-1
    // until bootstrap), so this is exact rather than sampled: the walk has to
    // have covered the whole gap, not merely moved.
    assert!(synced.sync_start_period >= 0, "a synced store must have bootstrapped");
    assert_eq!(
        synced.sync_start_period as u64, anchor_period,
        "catch-up started from a different period than the anchor we pinned"
    );
    assert!(
        synced.period > synced.sync_start_period as u64,
        "reached SYNCED without advancing a period, so the catch-up walk went untested"
    );
}
