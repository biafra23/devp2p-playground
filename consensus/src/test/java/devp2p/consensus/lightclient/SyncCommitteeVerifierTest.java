package devp2p.consensus.lightclient;

import devp2p.consensus.types.SyncAggregate;
import devp2p.consensus.types.SyncCommittee;
import devp2p.consensus.types.BeaconBlockHeader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SyncCommitteeVerifier participation counting and rejection logic.
 */
class SyncCommitteeVerifierTest {

    @Test
    void rejectsBelowTwoThirdsParticipation() {
        // Below 2/3 of 512 = need < 342 bits set
        // Build a SyncAggregate with only 341 bits set (just below threshold)
        byte[] bits = new byte[64]; // all zero
        // Set first 341 bits
        for (int i = 0; i < 341; i++) {
            bits[i / 8] |= (1 << (i % 8));
        }
        byte[] sig = new byte[96];
        byte[] syncAggSsz = new byte[64 + 96];
        System.arraycopy(bits, 0, syncAggSsz, 0, 64);
        System.arraycopy(sig, 0, syncAggSsz, 64, 96);
        SyncAggregate agg = SyncAggregate.decode(syncAggSsz);

        assertEquals(341, agg.countParticipants());

        // Build dummy sync committee
        byte[] pubkeysBytes = new byte[512 * 48]; // all zeros
        byte[] aggregatePubkey = new byte[48];
        byte[] committeeSsz = new byte[512 * 48 + 48];
        System.arraycopy(pubkeysBytes, 0, committeeSsz, 0, 512 * 48);
        System.arraycopy(aggregatePubkey, 0, committeeSsz, 512 * 48, 48);
        SyncCommittee committee = SyncCommittee.decode(committeeSsz);

        BeaconBlockHeader header = new BeaconBlockHeader(1L, 0L, new byte[32], new byte[32], new byte[32]);
        byte[] forkVersion = {0x05, 0x00, 0x00, 0x00};
        byte[] gvr = new byte[32];

        // 341 < 342 (2/3 of 512), should be rejected
        assertFalse(SyncCommitteeVerifier.verify(agg, committee, header, forkVersion, gvr));
    }

    @Test
    void rejectsZeroParticipation() {
        byte[] bits = new byte[64]; // all zero
        byte[] sig = new byte[96];
        byte[] syncAggSsz = new byte[160];
        SyncAggregate agg = SyncAggregate.decode(syncAggSsz);
        assertEquals(0, agg.countParticipants());

        byte[] committeeSsz = new byte[512 * 48 + 48];
        SyncCommittee committee = SyncCommittee.decode(committeeSsz);
        BeaconBlockHeader header = new BeaconBlockHeader(0L, 0L, new byte[32], new byte[32], new byte[32]);

        assertFalse(SyncCommitteeVerifier.verify(agg, committee, header,
                new byte[4], new byte[32]));
    }

    @Test
    void countParticipantsAtExactTwoThirds() {
        // 342 = ceil(512 * 2/3) — minimum for acceptance
        byte[] bits = new byte[64];
        for (int i = 0; i < 342; i++) {
            bits[i / 8] |= (1 << (i % 8));
        }
        byte[] syncAggSsz = new byte[160];
        System.arraycopy(bits, 0, syncAggSsz, 0, 64);
        SyncAggregate agg = SyncAggregate.decode(syncAggSsz);
        assertEquals(342, agg.countParticipants());
        // Participation check passes (but BLS will fail with all-zero inputs)
        // 342 * 3 = 1026 >= 512 * 2 = 1024 → passes participation check
    }

    @Test
    void getBitCorrectness() {
        byte[] bits = new byte[64];
        bits[0] = 0b00000101; // bits 0 and 2 set
        bits[1] = (byte) 0b10000000; // bit 15 set
        byte[] syncAggSsz = new byte[160];
        System.arraycopy(bits, 0, syncAggSsz, 0, 64);
        SyncAggregate agg = SyncAggregate.decode(syncAggSsz);

        assertTrue(agg.getBit(0));
        assertFalse(agg.getBit(1));
        assertTrue(agg.getBit(2));
        assertTrue(agg.getBit(15));
        assertFalse(agg.getBit(16));
    }
}
