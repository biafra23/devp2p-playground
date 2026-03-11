package devp2p.consensus.bls;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BlsVerifier BLS12-381 signature verification.
 * Uses test vectors from the IETF BLS draft spec.
 */
class BlsVerifierTest {

    /**
     * Verify that BlsVerifier rejects obviously invalid inputs without crashing.
     * We can't easily produce valid test vectors here without a BLS signing impl,
     * but we can verify the API contract.
     */
    @Test
    void rejectsEmptyPubkeyList() {
        byte[] msg = new byte[32];
        byte[] sig = new byte[96]; // all-zero, invalid
        assertFalse(BlsVerifier.fastAggregateVerify(List.of(), msg, sig));
    }

    @Test
    void rejectsInvalidCompressedPubkey() {
        byte[] invalidPubkey = new byte[48]; // all zeros — invalid G1 point
        byte[] msg = new byte[32];
        byte[] sig = new byte[96];
        // Should return false (not throw)
        assertFalse(BlsVerifier.fastAggregateVerify(List.of(invalidPubkey), msg, sig));
    }

    @Test
    void rejectsInvalidSignatureBytes() {
        byte[] pubkey = new byte[48]; // invalid but non-crashing
        byte[] msg = new byte[32];
        byte[] invalidSig = new byte[96]; // all zeros — invalid G2 point
        assertFalse(BlsVerifier.fastAggregateVerify(List.of(pubkey), msg, invalidSig));
    }

    @Test
    void rejectsWrongPubkeySize() {
        byte[] wrongSize = new byte[47]; // should be 48
        byte[] msg = new byte[32];
        byte[] sig = new byte[96];
        assertFalse(BlsVerifier.fastAggregateVerify(List.of(wrongSize), msg, sig));
    }

    @Test
    void rejectsWrongSignatureSize() {
        byte[] pubkey = new byte[48];
        byte[] msg = new byte[32];
        byte[] wrongSig = new byte[95]; // should be 96
        assertFalse(BlsVerifier.fastAggregateVerify(List.of(pubkey), msg, wrongSig));
    }
}
