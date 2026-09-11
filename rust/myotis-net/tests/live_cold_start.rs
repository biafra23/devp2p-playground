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
//! # old anchor: pass one that is genuinely behind. A good source is the
//! # PREVIOUS release's embedded checkpoint, which was a real finalized root
//! # and is one release old by construction:
//! #   git show v0.1.8:rust/myotis-net/src/sync.rs | grep -A6 '@checkpoint:gnosis:begin'
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

/// Network under test. Gnosis by default: its light-client servers are almost
/// all Lighthouse, which is the population that stopped answering in #422.
fn config_for_env() -> ChainConfig {
    match std::env::var("NET").unwrap_or_else(|_| "gnosis".into()).as_str() {
        "mainnet" => ChainConfig::mainnet(),
        "sepolia" => ChainConfig::sepolia(),
        _ => ChainConfig::gnosis(),
    }
}

fn init_tracing() {
    let _ = tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info,myotis_net=debug".into()),
        )
        .try_init();
}

/// Drive the handle to SYNCED or the deadline. Returns the first synced status
/// and the period the store held once it had bootstrapped, so a caller can
/// assert the catch-up actually walked.
async fn run_to_synced(
    handle: &SyncHandle,
    label: &str,
    budget: Duration,
) -> (Option<myotis_net::SyncStatus>, Option<u64>) {
    let deadline = tokio::time::Instant::now() + budget;
    let mut period_after_bootstrap = None;
    while tokio::time::Instant::now() < deadline {
        tokio::time::sleep(Duration::from_secs(5)).await;
        let s = handle.status();
        eprintln!(
            "[{label}] state={} period={} finalized_slot={} peers={} served/min={}",
            s.state, s.period, s.finalized_slot, s.peer_count, s.served_peers_last_min
        );
        // First status with a committee in hand: the bootstrap landed.
        if period_after_bootstrap.is_none() && s.period > 0 {
            period_after_bootstrap = Some(s.period);
        }
        if s.state == SyncState::Synced {
            return (Some(s), period_after_bootstrap);
        }
    }
    (None, period_after_bootstrap)
}

#[tokio::test(flavor = "multi_thread")]
#[ignore = "live network test: cold start with NO pinned peers, takes minutes"]
async fn cold_start_without_static_peers_syncs_through_discovery() {
    init_tracing();
    let mut config = config_for_env();
    let pinned = config.static_peers.len();
    // The crutch under test. Discovery keeps its bootnodes — removing those
    // would test nothing but "a node with no way in cannot get in".
    config.static_peers.clear();
    assert!(!config.bootstrap_enrs.is_empty(), "discovery needs its bootnodes");

    let handle = SyncHandle::start(config).expect("sync start");
    let (synced, _) = run_to_synced(&handle, "no-pins", Duration::from_secs(900)).await;
    handle.stop().await;

    assert!(
        synced.is_some(),
        "cold start with all {pinned} pinned peers removed did not reach SYNCED in 15 min — \
         discovery alone could not find a light-client server, which is the #422 condition"
    );
}

#[tokio::test(flavor = "multi_thread")]
#[ignore = "live network test: cold start from an OLD anchor, takes many minutes"]
async fn cold_start_from_an_old_anchor_walks_periods_to_head() {
    init_tracing();
    let mut config = config_for_env();

    // An anchor that is genuinely behind. Without one the walk is 0-2 periods
    // and the test passes vacuously, so require it rather than defaulting.
    let Ok(root_hex) = std::env::var("MYOTIS_TEST_ANCHOR_ROOT") else {
        eprintln!(
            "skipping: set MYOTIS_TEST_ANCHOR_ROOT + MYOTIS_TEST_ANCHOR_SLOT to an anchor \
             that is several periods behind (see this file's header for where to get one)"
        );
        return;
    };
    let slot: u64 = std::env::var("MYOTIS_TEST_ANCHOR_SLOT")
        .expect("MYOTIS_TEST_ANCHOR_SLOT must accompany MYOTIS_TEST_ANCHOR_ROOT")
        .parse()
        .expect("anchor slot must be a number");
    let root_hex = root_hex.trim_start_matches("0x");
    assert_eq!(root_hex.len(), 64, "anchor root must be 32 bytes of hex");
    let mut root = [0u8; 32];
    for (i, b) in root.iter_mut().enumerate() {
        *b = u8::from_str_radix(&root_hex[i * 2..i * 2 + 2], 16).expect("anchor root hex");
    }

    let anchor_period = slot / config.slots_per_period();
    let wall_period = config.wall_clock_period();
    assert!(
        wall_period > anchor_period,
        "anchor period {anchor_period} is not behind the wall clock ({wall_period}) — \
         this test would prove nothing"
    );
    let behind = wall_period - anchor_period;
    eprintln!("[old-anchor] anchor period {anchor_period}, wall {wall_period} ({behind} behind)");

    config.checkpoint_root = root;
    config.checkpoint_slot = slot;
    // An anchor this old is past the weak-subjectivity bound, so the engine
    // parks in STALE_ANCHOR until a host consents. Consent here: the point of
    // the test is the catch-up walk behind that gate, and the gate itself has
    // its own coverage.
    config.ws_policy.accept_stale_anchor.store(true, Ordering::Relaxed);

    let handle = SyncHandle::start(config).expect("sync start");
    // Peer-quota-bound: ~1 update per 10 s per serving peer, fanned out.
    let (synced, after_bootstrap) =
        run_to_synced(&handle, "old-anchor", Duration::from_secs(1800)).await;
    handle.stop().await;

    let synced = synced.unwrap_or_else(|| {
        panic!(
            "cold start {behind} periods behind did not reach SYNCED in 30 min — \
             the bootstrap may have landed but catch-up made no progress, which is \
             exactly the #422 stall"
        )
    });
    let start = after_bootstrap.expect("a synced store must have bootstrapped");
    assert!(
        synced.period > start,
        "reached SYNCED without advancing a single period (bootstrapped at {start}) — \
         the anchor was not actually behind, so the catch-up walk went untested"
    );
}
