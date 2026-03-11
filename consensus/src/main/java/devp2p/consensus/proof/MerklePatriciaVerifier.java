package devp2p.consensus.proof;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.crypto.Hash;
import org.apache.tuweni.rlp.RLP;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/**
 * Verifies Ethereum Merkle-Patricia Trie proofs (as used in the eth_getProof / snap/1 response)
 * against a trusted state root.
 *
 * The proof is a list of RLP-encoded trie nodes forming a path from the root down to the
 * account leaf. The path key is keccak256(address), traversed as nibbles.
 *
 * Node types:
 * - Branch node:    17-item RLP list. Items [0..15] are child references, item [16] is value.
 * - Extension node: 2-item RLP list. Item [0] is compact-encoded shared prefix, item [1] is child ref.
 * - Leaf node:      2-item RLP list. Item [0] is compact-encoded remaining key, item [1] is RLP-encoded account.
 *
 * Compact encoding:
 *   - First nibble of first byte indicates type and whether length is odd/even:
 *       0 → extension, even length  (skip first byte's low nibble, it's padding)
 *       1 → extension, odd length   (first byte's low nibble is first nibble of path)
 *       2 → leaf, even length
 *       3 → leaf, odd length
 *
 * Node references:
 *   - If an RLP value in a branch/extension is exactly 32 bytes → it's a hash reference.
 *   - If it's shorter (embedded/inline node) → it IS the node RLP directly.
 *   - An empty reference is the RLP empty string (0x80) or empty bytes.
 */
public class MerklePatriciaVerifier {

    private MerklePatriciaVerifier() {}

    /**
     * Verify that the account proof is valid against the given state root.
     *
     * @param stateRoot      32-byte trusted state root (from beacon chain)
     * @param address        20-byte Ethereum address
     * @param proofNodes     list of RLP-encoded trie nodes forming the proof path
     * @param expectedNonce  expected nonce (-1 to skip check)
     * @param expectedBalance expected balance as decimal string, or null to skip check
     * @return true if proof is internally consistent AND roots to stateRoot
     */
    public static boolean verify(byte[] stateRoot, byte[] address,
                                  List<byte[]> proofNodes,
                                  long expectedNonce, String expectedBalance) {

        if (proofNodes == null || proofNodes.isEmpty()) return false;
        if (stateRoot == null || stateRoot.length != 32) return false;
        if (address == null || address.length != 20) return false;

        // The key path is keccak256(address), as 64 nibbles
        byte[] keyHash = keccak256(address); // 32 bytes = 64 nibbles
        byte[] nibbles = toNibbles(keyHash);  // 64 nibbles

        byte[] expectedNodeHash = stateRoot;
        int nibbleOffset = 0;

        for (int i = 0; i < proofNodes.size(); i++) {
            byte[] nodeRlp = proofNodes.get(i);

            // Verify this node's hash matches what we expect
            byte[] nodeHash = keccak256(nodeRlp);
            if (!Arrays.equals(nodeHash, expectedNodeHash)) {
                return false; // hash mismatch in proof chain
            }

            // Decode the RLP node
            List<byte[]> items = decodeRlpList(nodeRlp);
            if (items == null) return false;

            if (items.size() == 17) {
                // Branch node
                if (nibbleOffset >= nibbles.length) {
                    // We've consumed all key nibbles — the value is in items[16]
                    // For account proofs the leaf value shouldn't be here; this is unusual
                    return false;
                }
                int nibble = nibbles[nibbleOffset] & 0xFF;
                byte[] childRef = items.get(nibble);

                if (childRef == null || childRef.length == 0) {
                    // No child in this branch — account doesn't exist
                    return false;
                }

                nibbleOffset++;

                if (i == proofNodes.size() - 1) {
                    // Last node — if the child ref is a leaf embedded inline, handle it
                    if (childRef.length < 32) {
                        // Inline node: childRef is the RLP of a leaf node
                        List<byte[]> leafItems = decodeRlpList(childRef);
                        if (leafItems == null || leafItems.size() != 2) return false;
                        return verifyLeaf(leafItems, nibbles, nibbleOffset,
                                expectedNonce, expectedBalance);
                    }
                    // Otherwise: we'd need one more node in the proof, but the list ended
                    return false;
                } else {
                    // Follow the child reference into the next proof node
                    if (childRef.length == 32) {
                        expectedNodeHash = childRef;
                    } else {
                        // Inline / embedded node: verify the next proof node IS this child
                        // (The next node's hash should equal keccak(childRef) only if >=32 bytes;
                        // for inline nodes, the RLP is embedded directly — the next proof node
                        // should match childRef exactly)
                        if (!Arrays.equals(proofNodes.get(i + 1), childRef)) {
                            return false;
                        }
                        expectedNodeHash = keccak256(childRef);
                    }
                }

            } else if (items.size() == 2) {
                // Extension or leaf node
                byte[] encodedPath = items.get(0);
                byte[] value = items.get(1);

                if (encodedPath == null || encodedPath.length == 0) return false;

                int firstHalfByte = (encodedPath[0] & 0xFF) >> 4;
                boolean isLeaf = (firstHalfByte == 2) || (firstHalfByte == 3);
                boolean isOdd = (firstHalfByte & 1) == 1;

                // Decode the nibbles embedded in this node's compact-encoded path
                byte[] nodeNibbles = compactToNibbles(encodedPath, isOdd);

                // Verify the path nibbles match our key's current position
                if (nibbleOffset + nodeNibbles.length > nibbles.length) {
                    return false; // path in proof is longer than remaining key
                }
                for (int j = 0; j < nodeNibbles.length; j++) {
                    if (nibbles[nibbleOffset + j] != nodeNibbles[j]) {
                        return false; // path mismatch
                    }
                }
                nibbleOffset += nodeNibbles.length;

                if (isLeaf) {
                    // Verify we've consumed exactly all key nibbles
                    if (nibbleOffset != nibbles.length) {
                        return false; // remaining key doesn't match
                    }
                    // value is the RLP-encoded account
                    return verifyAccountValue(value, expectedNonce, expectedBalance);

                } else {
                    // Extension node — follow the reference to the next node
                    if (i == proofNodes.size() - 1) {
                        return false; // extension at end of proof — missing next node
                    }

                    if (value.length == 32) {
                        expectedNodeHash = value;
                    } else {
                        // Inline child
                        if (!Arrays.equals(proofNodes.get(i + 1), value)) {
                            return false;
                        }
                        expectedNodeHash = keccak256(value);
                    }
                }
            } else {
                // Neither 2-item nor 17-item — invalid node structure
                return false;
            }
        }

        // If we've iterated through all nodes without returning true from a leaf,
        // the proof is incomplete
        return false;
    }

    // -------------------------------------------------------------------------
    // Leaf value verification
    // -------------------------------------------------------------------------

    /**
     * Verify a leaf node: check path matches remaining nibbles, then decode the account.
     */
    private static boolean verifyLeaf(List<byte[]> leafItems, byte[] nibbles, int nibbleOffset,
                                       long expectedNonce, String expectedBalance) {
        byte[] encodedPath = leafItems.get(0);
        byte[] value = leafItems.get(1);

        if (encodedPath == null || encodedPath.length == 0) return false;

        int firstHalfByte = (encodedPath[0] & 0xFF) >> 4;
        boolean isLeaf = (firstHalfByte == 2) || (firstHalfByte == 3);
        if (!isLeaf) return false;

        boolean isOdd = (firstHalfByte & 1) == 1;
        byte[] nodeNibbles = compactToNibbles(encodedPath, isOdd);

        // Verify path matches remaining nibbles
        if (nibbleOffset + nodeNibbles.length != nibbles.length) return false;
        for (int j = 0; j < nodeNibbles.length; j++) {
            if (nibbles[nibbleOffset + j] != nodeNibbles[j]) return false;
        }

        return verifyAccountValue(value, expectedNonce, expectedBalance);
    }

    /**
     * Decode account RLP [nonce, balance, storageRoot, codeHash] and optionally
     * verify nonce and/or balance against expected values.
     *
     * Account RLP: [nonce (integer), balance (integer), storageRoot (bytes32), codeHash (bytes32)]
     */
    private static boolean verifyAccountValue(byte[] accountRlp,
                                               long expectedNonce,
                                               String expectedBalance) {
        if (accountRlp == null || accountRlp.length == 0) return false;

        try {
            Bytes rlpBytes = Bytes.wrap(accountRlp);
            long[] nonceHolder = new long[1];
            BigInteger[] balanceHolder = new BigInteger[1];

            RLP.decodeList(rlpBytes, reader -> {
                // nonce: uint
                Bytes nonceBytes = reader.readValue();
                nonceHolder[0] = nonceBytes.isEmpty() ? 0L : nonceBytes.toLong();

                // balance: arbitrary-precision big integer
                Bytes balanceBytes = reader.readValue();
                if (balanceBytes.isEmpty()) {
                    balanceHolder[0] = BigInteger.ZERO;
                } else {
                    balanceHolder[0] = new BigInteger(1, balanceBytes.toArrayUnsafe());
                }

                // storageRoot and codeHash (32 bytes each) — we skip validation here;
                // the state root check already validates consistency
                reader.readValue(); // storageRoot
                reader.readValue(); // codeHash

                return null;
            });

            // Validate expected nonce if requested
            if (expectedNonce >= 0 && nonceHolder[0] != expectedNonce) {
                return false;
            }

            // Validate expected balance if requested
            if (expectedBalance != null) {
                BigInteger expected;
                if (expectedBalance.startsWith("0x") || expectedBalance.startsWith("0X")) {
                    expected = new BigInteger(expectedBalance.substring(2), 16);
                } else {
                    expected = new BigInteger(expectedBalance, 10);
                }
                if (!balanceHolder[0].equals(expected)) {
                    return false;
                }
            }

            return true;

        } catch (Exception e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // RLP decoding helpers
    // -------------------------------------------------------------------------

    /**
     * Decode an RLP-encoded list and return each item's raw bytes.
     *
     * <p>This is a manual low-level RLP parser so we can capture the raw bytes of each
     * item (including nested lists / inline nodes) without relying on Tuweni's RLPReader
     * API for re-encoding.
     *
     * <p>Returns null if the input is not a valid RLP list.
     */
    private static List<byte[]> decodeRlpList(byte[] rlp) {
        if (rlp == null || rlp.length == 0) return null;

        // An RLP list starts with a byte >= 0xC0
        int first = rlp[0] & 0xFF;
        if (first < 0xC0) return null; // not a list

        int listPayloadOffset;
        int listPayloadLength;
        if (first <= 0xF7) {
            // Short list: first byte encodes (0xC0 + length)
            listPayloadLength = first - 0xC0;
            listPayloadOffset = 1;
        } else {
            // Long list: next (first - 0xF7) bytes are the length
            int lenLen = first - 0xF7;
            if (rlp.length < 1 + lenLen) return null;
            listPayloadLength = 0;
            for (int i = 0; i < lenLen; i++) {
                listPayloadLength = (listPayloadLength << 8) | (rlp[1 + i] & 0xFF);
            }
            listPayloadOffset = 1 + lenLen;
        }

        if (listPayloadOffset + listPayloadLength > rlp.length) return null;

        java.util.List<byte[]> items = new java.util.ArrayList<>();
        int pos = listPayloadOffset;
        int end = listPayloadOffset + listPayloadLength;

        while (pos < end) {
            int[] itemRange = rlpItemRange(rlp, pos, end);
            if (itemRange == null) return null;

            int itemStart = itemRange[0];
            int itemEnd   = itemRange[1];
            int itemFirst = rlp[pos] & 0xFF;

            if (itemFirst >= 0xC0) {
                // Nested list — return the full raw RLP encoding (header + payload)
                byte[] nested = new byte[itemEnd - pos];
                System.arraycopy(rlp, pos, nested, 0, nested.length);
                items.add(nested);
            } else {
                // Value — return only the payload bytes (strip RLP header)
                byte[] payload = new byte[itemEnd - itemStart];
                System.arraycopy(rlp, itemStart, payload, 0, payload.length);
                items.add(payload);
            }
            pos = itemEnd;
        }

        return items;
    }

    /**
     * Compute the [payloadStart, itemEnd] range for an RLP item starting at {@code pos}.
     * Returns null on malformed input.
     */
    private static int[] rlpItemRange(byte[] data, int pos, int end) {
        if (pos >= end) return null;
        int first = data[pos] & 0xFF;

        if (first < 0x80) {
            // Single byte value
            return new int[]{pos, pos + 1};
        } else if (first <= 0xB7) {
            // Short string/bytes: next (first - 0x80) bytes are payload
            int payloadLen = first - 0x80;
            if (pos + 1 + payloadLen > end) return null;
            return new int[]{pos + 1, pos + 1 + payloadLen};
        } else if (first <= 0xBF) {
            // Long string: next (first - 0xB7) bytes encode payload length
            int lenLen = first - 0xB7;
            if (pos + 1 + lenLen > end) return null;
            int payloadLen = 0;
            for (int i = 0; i < lenLen; i++) {
                payloadLen = (payloadLen << 8) | (data[pos + 1 + i] & 0xFF);
            }
            int headerLen = 1 + lenLen;
            if (pos + headerLen + payloadLen > end) return null;
            return new int[]{pos + headerLen, pos + headerLen + payloadLen};
        } else if (first <= 0xF7) {
            // Short list: (first - 0xC0) bytes of payload
            int payloadLen = first - 0xC0;
            if (pos + 1 + payloadLen > end) return null;
            return new int[]{pos + 1, pos + 1 + payloadLen};
        } else {
            // Long list: next (first - 0xF7) bytes encode payload length
            int lenLen = first - 0xF7;
            if (pos + 1 + lenLen > end) return null;
            int payloadLen = 0;
            for (int i = 0; i < lenLen; i++) {
                payloadLen = (payloadLen << 8) | (data[pos + 1 + i] & 0xFF);
            }
            int headerLen = 1 + lenLen;
            if (pos + headerLen + payloadLen > end) return null;
            return new int[]{pos + headerLen, pos + headerLen + payloadLen};
        }
    }

    // -------------------------------------------------------------------------
    // Nibble / compact encoding helpers
    // -------------------------------------------------------------------------

    /**
     * Convert a byte array to a nibble array (each byte becomes 2 nibbles, high nibble first).
     */
    static byte[] toNibbles(byte[] bytes) {
        byte[] nibbles = new byte[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            nibbles[2 * i]     = (byte) ((bytes[i] >> 4) & 0x0F);
            nibbles[2 * i + 1] = (byte) (bytes[i] & 0x0F);
        }
        return nibbles;
    }

    /**
     * Decode the nibbles from a compact-encoded path.
     *
     * Compact encoding layout:
     *   - High nibble of first byte encodes type + odd/even flag:
     *       0 → extension, even (skip entire first byte, no nibbles from it)
     *       1 → extension, odd  (low nibble of first byte is first nibble)
     *       2 → leaf, even      (skip entire first byte)
     *       3 → leaf, odd       (low nibble of first byte is first nibble)
     *   - Remaining bytes contribute 2 nibbles each.
     *
     * @param compact compact-encoded byte array
     * @param isOdd   whether the original nibble length was odd
     * @return the decoded nibbles
     */
    static byte[] compactToNibbles(byte[] compact, boolean isOdd) {
        if (compact.length == 0) return new byte[0];

        if (isOdd) {
            // First nibble of the path is the low nibble of compact[0]
            // Remaining nibbles come from compact[1], compact[2], ...
            int totalNibbles = 1 + (compact.length - 1) * 2;
            byte[] nibbles = new byte[totalNibbles];
            nibbles[0] = (byte) (compact[0] & 0x0F);
            for (int i = 1; i < compact.length; i++) {
                nibbles[1 + (i - 1) * 2]     = (byte) ((compact[i] >> 4) & 0x0F);
                nibbles[1 + (i - 1) * 2 + 1] = (byte) (compact[i] & 0x0F);
            }
            return nibbles;
        } else {
            // Even: skip compact[0] entirely, decode nibbles from compact[1..]
            int totalNibbles = (compact.length - 1) * 2;
            byte[] nibbles = new byte[totalNibbles];
            for (int i = 1; i < compact.length; i++) {
                nibbles[(i - 1) * 2]     = (byte) ((compact[i] >> 4) & 0x0F);
                nibbles[(i - 1) * 2 + 1] = (byte) (compact[i] & 0x0F);
            }
            return nibbles;
        }
    }

    // -------------------------------------------------------------------------
    // Crypto
    // -------------------------------------------------------------------------

    /**
     * Compute keccak256 of the input bytes.
     */
    private static byte[] keccak256(byte[] input) {
        return Hash.keccak256(Bytes.wrap(input)).toArrayUnsafe();
    }
}
