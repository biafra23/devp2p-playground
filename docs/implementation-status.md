# Architecture vs Implementation Status

Comparison of the [architecture document](architecture-doc.md) against what is actually implemented in the codebase.

## 1. Establishing a Trusted Chain Head — Sync Committees
**POC: Implemented**

- Beacon light client with bootstrap, finality updates, and sync committee rotation (`BeaconLightClient`, `LightClientProcessor`)
- BLS12-381 signature verification with 2/3 supermajority check (`SyncCommitteeVerifier`, `BlsVerifier`)
- libp2p networking with Noise XX, Yamux/Mplex (`BeaconP2PService`)
- All four light client req/resp protocols implemented (bootstrap, updates_by_range, finality_update, optimistic_update)
- Execution state root extraction from beacon blocks
- SSZ types for all beacon structures (47 files in `consensus/`)

**Not implemented:** discv5 for CL peer discovery — CL peers are hardcoded multiaddrs in `NetworkConfig` instead.

## 2. Verifying Historical Blocks — Trusted Accumulator Snapshots
**POC: Partially implemented**

- Header chain verification up to 8,192 blocks from the finalized block is implemented (walk parent hashes)
- Block verification against beacon `ExecutionPayloadHeader.block_hash` works

**Not implemented:**
- `historical_summaries` / `historical_roots` lookup from beacon state (would remove the 8,192-block limit)
- Pre-merge epoch hash accumulator (`premergeacc.bin`) — the README mentions it as a trust anchor but the code doesn't use it for block verification yet
- Pre-merge blocks return `failReason: "preMergeBlock"` with no verification path

## 3. Transaction History — TrueBlocks via IPFS
**POC: Implemented**

- TrueBlocks manifest fetched from IPFS (hardcoded CID)
- Bloom filter + index chunk download per address
- Block bodies fetched from devp2p peers, transactions extracted and RLP-parsed
- Supports legacy, EIP-2930, EIP-1559, EIP-4844 tx types

**Not implemented:**
- Transaction verification against `transactionsRoot` (the `verified` field is always `false`)
- Dynamic manifest CID discovery (hardcoded, stale)
- Balance reconciliation for completeness checking

## 4. Fetching and Verifying Block Data — devp2p
**POC: Implemented**

- Full devp2p stack: discv4 discovery, RLPx ECIES handshake, eth/67-69 protocol
- `GetBlockHeaders` and `GetBlockBodies` implemented
- Block header verification against beacon chain (direct state root match or header chain)
- Peer caching across sessions

**Not implemented:**
- `GetReceipts` — receipt fetching and verification against `receiptsRoot`
- DNS discovery for bootstrap peers (uses hardcoded bootnodes only)
- EIP-4444 fallback strategies

## 5. State Data — SNAP Protocol
**POC: Implemented**

- snap/1 protocol negotiated alongside eth
- `GetAccountRange` with Merkle-Patricia proof verification (`MerklePatriciaVerifier`)
- `GetStorageRanges` with storage proof verification
- ERC-20 balance lookup via `keccak256(abi.encode(holder, slot))` mapping
- Full beacon chain cross-verification (proof -> state root -> beacon finalized root)

**Not implemented:**
- `GetTrieNodes` (alternative trie path approach)
- NFT ownership queries (same mechanism but not exposed via IPC)
- Vyper storage slot layout support

## 6. ENS Resolution — Via SNAP Storage Proofs
**Not implemented**

The primitives exist (storage proofs work), but there is no ENS-specific command or logic to do the multi-step registry -> resolver lookup.

## 7. Submitting Signed Transactions — devp2p Transaction Gossip
**Not implemented**

No `Transactions`, `NewPooledTransactionHashes`, or `GetPooledTransactions` message handling. The eth handler only covers handshake + block header/body + snap queries.

## 8. Gas Estimation
**Not implemented**

`baseFeePerGas` is available in `BlockHeader` and returned in `get-block`, but there is no dedicated gas estimation command or priority fee calculation logic.

## Summary

| Architecture Section | Status | Key Gap |
|---|---|---|
| 1. Sync Committees (CL light client) | **Implemented** | No discv5, hardcoded CL peers |
| 2. Historical Block Verification | **Partial** | No accumulator snapshots, 8192-block limit |
| 3. TrueBlocks Transaction History | **Implemented** | No tx verification against `transactionsRoot` |
| 4. Block Data via devp2p | **Implemented** | No receipts, no DNS discovery |
| 5. State Data via SNAP | **Implemented** | No `GetTrieNodes`, no NFT/Vyper support |
| 6. ENS Resolution | **Not started** | Primitives exist, no ENS logic |
| 7. Transaction Submission | **Not started** | No tx gossip messages |
| 8. Gas Estimation | **Not started** | `baseFeePerGas` available but no command |

The core verification pipeline (sync committees -> state root -> Merkle proofs) is fully functional end-to-end. The biggest gaps are on the "wallet action" side: submitting transactions, ENS, and gas estimation.
