# devp2p Playground

A from-scratch Ethereum devp2p implementation in Java 21. Connects to the Ethereum mainnet (or testnets) using the devp2p protocol stack: discv4 peer discovery, RLPx encrypted transport, and eth/67-69 sub-protocol. Includes a beacon chain light client for consensus-layer state root verification and snap/1 support for account and storage lookups with Merkle proofs and cryptographic verification back to beacon chain finality.

## Requirements

- Java 21+
- Gradle (wrapper included)

## Build

```bash
# Build all modules (includes tests)
./gradlew build

# Compile only (skip tests)
./gradlew compileJava

# Run tests
./gradlew test
```

## Run

The application operates in two modes: **daemon** and **client**. The daemon discovers peers, maintains connections, and listens for commands on a Unix domain socket (`/tmp/devp2p.sock`). The client sends a single command to the running daemon and exits.

### Start the daemon

```bash
# Mainnet (default)
./gradlew :app:run

# Testnet
./gradlew :app:run -Pnetwork=sepolia
./gradlew :app:run -Pnetwork=holesky

# Custom port (default: 30303)
./gradlew :app:run -Pport=30304
```

The daemon runs in the foreground. It discovers peers via discv4 (Kademlia DHT), establishes RLPx encrypted connections, and performs eth protocol handshakes. A beacon chain light client syncs finalized state roots from the consensus layer.

### Stop the daemon

```bash
./gradlew :app:run -Pargs=stop
```

## Query commands

All commands are sent to the running daemon via IPC. Responses are JSON.

### Status

```bash
./gradlew :app:run -Pargs=status
```

Returns uptime, discovered/connected/ready peer counts, snap peer count, and backoff/blacklist stats.

### Peers

```bash
./gradlew :app:run -Pargs=peers
```

Returns discovered peers (from the Kademlia table) and connected peers with their state, snap support, and client ID.

### Beacon status

```bash
./gradlew :app:run -Pargs=beacon-status
```

Returns beacon light client sync state: finalized slot, optimistic slot, execution state root, execution block number, known state root count, connected CL peers, and sync status.

### Get block headers

```bash
# Get 3 headers starting at block 21000000
./gradlew :app:run -Pargs="get-headers 21000000 3"
```

### Get block (header + body)

```bash
./gradlew :app:run -Pargs="get-block 21000000"
```

### Get account

```bash
./gradlew :app:run -Pargs="get-account 0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"
```

Returns account data (nonce, balance, storage root, code hash) with a Merkle-Patricia proof and cryptographic verification. The response includes a `verification` object with:

- `peerProofValid` -- the Merkle proof verifies against the peer's state root
- `beaconChainVerified` -- the peer's state root has been verified against the beacon chain
- `verifyMethod` -- how verification was achieved:
  - `stateRootMatch` -- the peer's state root exactly matches a beacon-attested execution state root
  - `headerChain` -- a verified chain of block headers links the beacon-finalized block to the peer's block (used when the peer is ahead of the finalized block)
- `matchedBeaconSlot` -- the beacon slot used as the trust anchor
- `blsVerified` -- whether the trust anchor was validated via BLS sync committee signatures

### Get storage

```bash
# Direct slot access
./gradlew :app:run -Pargs="get-storage 0x<contract> <slot>"

# ERC-20 balance lookup (mapping slot with holder address)
./gradlew :app:run -Pargs="get-storage 0x<token> <slot> 0x<holder>"
```

Returns storage slot data for a contract with Merkle-Patricia proof verification. For ERC-20 tokens, pass the mapping slot number and holder address to compute `keccak256(abi.encode(holder, slot))`. The response includes:

- `value` / `valueDecimal` -- the storage slot value (hex and decimal)
- `storageRoot` -- the account's storage trie root
- `storageProofValid` -- the storage proof verifies against the account's storage root
- `beaconChainVerified` -- same header chain verification as `get-account`

### Dial a specific peer

```bash
./gradlew :app:run -Pargs="dial enode://..."
```

## Helper scripts

Convenience scripts that wrap the Gradle commands and format the output with `jq`:

### `peers.sh`

Lists connected peers in READY state, sorted by snap support and client ID:

```bash
./peers.sh
# Output:
# 1.2.3.4:30303 snap=true Geth/v1.15.0/linux-amd64/go1.23.4
# 5.6.7.8:30303 snap=true Nethermind/v1.30.0/...
```

### `status.sh`

Prints daemon status as JSON:

```bash
./status.sh
# {"ok":true,"state":"RUNNING","uptimeSeconds":120,"discoveredPeers":214,...}
```

### `beacon-status.sh`

Prints beacon light client sync status:

```bash
./beacon-status.sh
```

## Beacon chain light client

The daemon includes a consensus-layer light client that tracks finalized state roots from the beacon chain. This enables trustless verification of account and storage proofs against the canonical chain state.

### Trust model

The only trust anchors are **sync committee BLS signatures** and the embedded historical hash accumulators. All data from devp2p and libp2p peers is cryptographically verified -- no trusted third-party RPCs or HTTP APIs are used in production.

### Verification flow

1. The beacon light client obtains a finalized execution state root (BLS-verified via sync committee signatures)
2. When a snap query returns account/storage data with a Merkle proof, the proof is first verified against the peer's state root
3. The peer's state root is then linked to the beacon-finalized state root via one of:
   - **Direct match** -- the peer's state root matches a known beacon-attested root
   - **Header chain verification** -- block headers are fetched in batches from the beacon-finalized block to the peer's block, verifying: (a) the first header's state root matches the beacon root, (b) consecutive parent hash chain integrity, (c) the last header's state root matches the peer's root

### Sync modes

The light client can sync from:
- **Beacon chain P2P network** (libp2p) -- fully decentralized, uses BLS-verified bootstrap and finality updates
- **Beacon node HTTP API** (seeded mode) -- seeds initial state from a local beacon node (e.g. Lighthouse on `http://localhost:5052`), then continues via P2P

Helper scripts for running a local beacon node are in `scripts/`:
- `scripts/lighthouse.sh` -- starts Lighthouse via Docker with checkpoint sync
- `scripts/lodestar.sh` -- starts Lodestar via Docker with checkpoint sync

## Architecture

Three Gradle modules:

- **core** -- cryptographic identity (`NodeKey`), data types (`BlockHeader`), ENR decoding
- **networking** -- protocol layers, all Netty-based:
  - `discv4` -- UDP peer discovery (ping/pong/findnode/neighbors)
  - `rlpx` -- TCP transport with EIP-8 ECIES handshake and AES-256-CTR framed channel
  - `eth` -- eth/67-69 sub-protocol (hello, status, block headers/bodies)
  - `snap` -- snap/1 sub-protocol (account range, storage range queries with Merkle proofs)
- **consensus** -- beacon chain light client (sync committee BLS verification), Merkle-Patricia proof verification
- **app** -- daemon/CLI entry point, Unix domain socket IPC server, peer caching

### Protocol flow

```
DiscV4Service (UDP)
  discovers peers
    --> RLPxConnector.connect() (TCP)
      --> ECIES handshake (HANDSHAKE_WRITE -> HANDSHAKE_READ -> FRAMED)
        --> EthHandler (AWAITING_HELLO -> AWAITING_STATUS -> READY)
          --> block headers, account/storage queries available
```

### Key dependencies

- **Tuweni 2.7.2** -- RLP encoding, SECP256K1, byte utilities
- **Netty 4.2.x** -- NIO transport
- **BouncyCastle** -- SECP256K1 crypto provider
- **jvm-libp2p** -- beacon chain P2P networking (consensus module)
