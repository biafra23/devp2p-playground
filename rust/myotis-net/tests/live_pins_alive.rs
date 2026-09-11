//! Are this build's configured CL peers and bootnodes actually alive?
//!
//! This is NOT a cold-start test and does not overlap `live_cold_start.rs`,
//! which deliberately blackholes every pin to prove discovery survives without
//! them. That test is blind to whether the real pins answer. This one asks
//! exactly that, because a silently rotted pin list is what #422 turned out to
//! be: the shipped build's servers had moved or gone, every automated check was
//! green, and a fresh install could not catch up at all.
//!
//! It is fast (seconds) and deterministic enough to read at release time:
//!
//! ```bash
//! NET=gnosis cargo test -p myotis-net --test live_pins_alive -- --ignored --nocapture
//! ```
//!
//! **Read the census, do not just read the exit code.** These are third-party
//! servers, so some are always down, and the run is only as trustworthy as the
//! host it runs from: a machine whose IP a Lighthouse node has banned sees that
//! node as `dial failed` even though it is healthy for everyone else (that ban
//! is what #422 was about, and a developer box that ran a pre-gossipsub build
//! carries it for 12 h). So the gate is a FLOOR — enough pins alive to
//! actually bootstrap a fresh install — and the per-pin lines above it are the
//! part a human acts on.
//!
//! What each line means:
//!
//! * a pin that does not answer — decommissioned, firewalled, or its address
//!   moved. Re-census it (`examples/period_census.rs`) or drop it.
//! * a pin that answers but does NOT advertise the light-client protocols —
//!   it is a beacon node that stopped serving light clients, so it occupies an
//!   un-evictable pool slot for nothing.
//! * a pin whose peer ID does not match — the server minted a new key (Nimbus
//!   does this per restart without `--netkey-file`); the pin is dead even
//!   though the host is up, and the dial reports `InvalidRemotePubKey` or a
//!   failed handshake rather than a clean refusal.
//! * bootnodes below the floor — discovery cannot seed, which strands a fresh
//!   install even when the pins are fine.

use std::sync::Arc;
use std::time::Duration;

use libp2p::Multiaddr;
use myotis_net::reqresp::{self, LocalStatus};
use myotis_net::status::StatusMessage;
use myotis_net::{protocols, ChainConfig, SyncHandle};

/// Per-peer dial + identify budget. Generous: a healthy server answers in well
/// under a second, and a slow-but-alive one must not be reported as dead.
const PIN_TIMEOUT: Duration = Duration::from_secs(20);

/// A first bootstrap request may legitimately MISS. roost's handler is a pure
/// cache read — the design forbids I/O on the swarm task — so an uncached root
/// answers `ResourceUnavailable` and queues a background fetch, expecting the
/// wallet to retry (`rust/roost/src/store.rs`, "a miss stays
/// ResourceUnavailable, with a background task filling it"). roost drops its
/// cached bootstraps whenever the fork/blob schedule changes, and starts empty
/// after a restart, so a single-shot check reports the project's own primary
/// server as dead on a cold cache. Retry the way a wallet does.
const MISS_RETRIES: usize = 2;
const MISS_BACKOFF: Duration = Duration::from_secs(6);

/// How long discovery gets to seed its routing table from the bootnodes.
const DISCOVERY_BUDGET: Duration = Duration::from_secs(60);

/// A routing table this size proves the bootnodes answered and the walk began.
/// Deliberately low: this asserts "discovery can seed", not "discovery is fast".
const MIN_TABLE_ENTRIES: usize = 8;

/// How many pinned peers must serve the anchor for the list to be doing its
/// job. A floor, not "all of them": pins are third-party hosts that come and
/// go, and requiring a clean sweep would fail for reasons that are not the
/// pin list's fault — which is how a release check becomes one people skip.
/// Two is the smallest number that is not a single point of failure.
const MIN_ALIVE_PINS: usize = 2;

fn config_for_env() -> ChainConfig {
    match std::env::var("NET").unwrap_or_else(|_| "gnosis".into()).as_str() {
        "mainnet" => ChainConfig::mainnet(),
        "sepolia" => ChainConfig::sepolia(),
        "gnosis" => ChainConfig::gnosis(),
        other => panic!("unknown NET {other:?} (want mainnet, sepolia or gnosis)"),
    }
}

fn init_tracing() {
    let _ = tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "warn,myotis_net=info".into()),
        )
        .try_init();
}

/// Every pinned CL peer must answer a `light_client_bootstrap` for this build's
/// own embedded checkpoint root. That is the strongest cheap check: it proves
/// the host is up, the peer ID still matches, the fork digest agrees, it serves
/// light clients, AND it still holds the anchor a fresh install starts from.
#[tokio::test(flavor = "multi_thread")]
#[ignore = "live network test: dials this build's pinned CL servers"]
async fn every_pinned_cl_peer_serves_this_builds_anchor() {
    init_tracing();
    let config = config_for_env();
    assert!(!config.static_peers.is_empty(), "this network pins no CL peers");

    let local = LocalStatus::new(StatusMessage {
        fork_digest: config.current_fork_digest(),
        finalized_root: config.checkpoint_root,
        finalized_epoch: config.checkpoint_slot / config.slots_per_epoch.max(1),
        head_root: config.checkpoint_root,
        head_slot: config.checkpoint_slot,
        earliest_available_slot: 0,
    });
    let client = reqresp::start_host(Arc::clone(&local)).expect("host");

    let mut dead = Vec::new();
    let mut alive = 0usize;
    for pin in &config.static_peers {
        let full: Multiaddr = pin.parse().expect("pinned multiaddr parses");
        let mut addr = Multiaddr::empty();
        let mut peer = None;
        for proto in full.iter() {
            if let libp2p::multiaddr::Protocol::P2p(id) = proto {
                peer = Some(id);
            } else {
                addr.push(proto);
            }
        }
        let peer = peer.expect("pinned multiaddr carries a peer id");
        // `None` until the first attempt runs; the loop below always sets it.
        let mut outcome = None;
        for attempt in 0..=MISS_RETRIES {
            if attempt > 0 {
                eprintln!("[pins] .... {addr} retrying after a miss ({attempt}/{MISS_RETRIES})");
                tokio::time::sleep(MISS_BACKOFF).await;
            }
            let req = myotis_net::codec::encode_request(&config.checkpoint_root);
            outcome = Some(
                tokio::time::timeout(
                    PIN_TIMEOUT,
                    client.request_raw(peer, addr.clone(), protocols::BOOTSTRAP, req),
                )
                .await,
            );
            // Only a cache MISS is worth retrying: a real bootstrap, a dial
            // failure and a timeout are all final answers.
            match &outcome {
                Some(Ok(Ok(raw))) if raw.len() <= 64 => continue,
                _ => break,
            }
        }
        match outcome.expect("the retry loop always runs at least once") {
            Ok(Ok(raw)) if raw.len() > 64 => {
                alive += 1;
                eprintln!("[pins] OK   {addr} ({} B bootstrap)", raw.len());
            }
            Ok(Ok(raw)) => {
                // Answered, but never with a bootstrap even after the retries:
                // it does not hold our anchor, or it refuses light-client
                // requests. `raw[0]` is the eth2 result code (3 =
                // ResourceUnavailable, which for roost means a cache miss its
                // background fetch did not fill in time).
                let code = raw.first().copied().unwrap_or(255);
                dead.push(format!(
                    "{pin} — answered {} B with result code {code}, not a bootstrap",
                    raw.len()
                ));
                eprintln!("[pins] THIN {addr} ({} B, code {code})", raw.len());
            }
            Ok(Err(e)) => {
                dead.push(format!("{pin} — {e}"));
                eprintln!("[pins] DEAD {addr} — {e}");
            }
            Err(_) => {
                dead.push(format!("{pin} — no answer in {PIN_TIMEOUT:?}"));
                eprintln!("[pins] DEAD {addr} — timeout");
            }
        }
    }
    client.shutdown().await;

    let total = config.static_peers.len();
    eprintln!("[pins] {alive} of {total} pinned {} peers served the anchor", config.name);
    if !dead.is_empty() {
        // Not a failure by itself — see the header — but always worth a human
        // look, because a dead pin is not free: it is exempt from eviction, so
        // it holds a pool slot and a targeted discovery lookup for the life of
        // the process.
        eprintln!(
            "[pins] {} did not serve it; re-census (examples/period_census.rs) or drop them:\n  {}",
            dead.len(),
            dead.join("\n  ")
        );
    }
    assert!(
        alive >= MIN_ALIVE_PINS,
        "only {alive} of {total} pinned CL peers on {} served this build's anchor (want at \
         least {MIN_ALIVE_PINS}) — a fresh install would depend entirely on discovery. \
         Before treating this as a pin-list problem, check whether THIS host is the \
         outlier: a Lighthouse node that has banned your IP reports as `dial failed` while \
         being healthy for everyone else. Dead pins:\n  {}",
        config.name,
        dead.join("\n  ")
    );
}

/// The bootnodes must be able to seed discovery. Without this a fresh install
/// has no way into the DHT, which no amount of working pins would fix.
#[tokio::test(flavor = "multi_thread")]
#[ignore = "live network test: seeds discv5 from this build's bootnodes"]
async fn the_bootnodes_can_seed_discovery() {
    init_tracing();
    let mut config = config_for_env();
    let bootnodes = config.bootstrap_enrs.len();
    assert!(bootnodes > 0, "this network configures no CL bootnodes");
    // Remove the pins so the routing table can only have come from bootnodes:
    // a pinned server's targeted lookup would otherwise mask a dead bootnode
    // list entirely.
    config.static_peers.clear();

    let handle = SyncHandle::start(config).expect("sync start");
    let deadline = tokio::time::Instant::now() + DISCOVERY_BUDGET;
    let mut best = 0usize;
    while tokio::time::Instant::now() < deadline {
        tokio::time::sleep(Duration::from_secs(5)).await;
        best = best.max(handle.status().discv5_table_size);
        eprintln!("[bootnodes] discv5 table: {best}");
        if best >= MIN_TABLE_ENTRIES {
            break;
        }
    }
    handle.stop().await;

    assert!(
        best >= MIN_TABLE_ENTRIES,
        "discv5 reached only {best} routing-table entries in {:?} from {bootnodes} configured \
         bootnodes (want >= {MIN_TABLE_ENTRIES}) — a fresh install cannot seed discovery, so it \
         depends entirely on the pins being alive",
        DISCOVERY_BUDGET
    );
}
