package devp2p.consensus.types;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * SSZ container: LightClientUpdate
 *
 * Fields (in order):
 *   attestedHeader           — LightClientHeader (variable)
 *   nextSyncCommittee        — SyncCommittee (24624B, fixed inline)
 *   nextSyncCommitteeBranch  — Vector[Bytes32, 5] (160B, fixed inline)
 *   finalizedHeader          — LightClientHeader (variable)
 *   finalityBranch           — Vector[Bytes32, 6] (192B, fixed inline)
 *   syncAggregate            — SyncAggregate (160B, fixed inline)
 *   signatureSlot            — uint64 (8B, fixed inline)
 *
 * SSZ fixed part (variable fields contribute 4B offsets):
 *   4B  offset to attestedHeader
 *   24624B  nextSyncCommittee
 *   160B  nextSyncCommitteeBranch
 *   4B  offset to finalizedHeader
 *   192B  finalityBranch
 *   160B  syncAggregate
 *   8B  signatureSlot
 *   Total fixed: 4 + 24624 + 160 + 4 + 192 + 160 + 8 = 25152 bytes
 */
public final class LightClientUpdate {

    public static final int FIXED_SIZE =
            4                           // attestedHeader offset
            + SyncCommittee.ENCODED_SIZE  // nextSyncCommittee = 24624
            + 5 * 32                    // nextSyncCommitteeBranch = 160
            + 4                         // finalizedHeader offset
            + 6 * 32                    // finalityBranch = 192
            + 160                       // syncAggregate
            + 8;                        // signatureSlot

    private final LightClientHeader attestedHeader;
    private final SyncCommittee nextSyncCommittee;
    private final byte[][] nextSyncCommitteeBranch; // 5 x 32
    private final LightClientHeader finalizedHeader;
    private final byte[][] finalityBranch;           // 6 x 32
    private final SyncAggregate syncAggregate;
    private final long signatureSlot;

    public LightClientUpdate(
            LightClientHeader attestedHeader,
            SyncCommittee nextSyncCommittee,
            byte[][] nextSyncCommitteeBranch,
            LightClientHeader finalizedHeader,
            byte[][] finalityBranch,
            SyncAggregate syncAggregate,
            long signatureSlot
    ) {
        this.attestedHeader = attestedHeader;
        this.nextSyncCommittee = nextSyncCommittee;
        this.nextSyncCommitteeBranch = nextSyncCommitteeBranch;
        this.finalizedHeader = finalizedHeader;
        this.finalityBranch = finalityBranch;
        this.syncAggregate = syncAggregate;
        this.signatureSlot = signatureSlot;
    }

    /**
     * Decode a LightClientUpdate from SSZ bytes.
     *
     * Fixed layout (bytes):
     *   [0..4)        offset to attestedHeader
     *   [4..24628)    nextSyncCommittee
     *   [24628..24788) nextSyncCommitteeBranch (5*32)
     *   [24788..24792) offset to finalizedHeader
     *   [24792..24984) finalityBranch (6*32)
     *   [24984..25144) syncAggregate (160)
     *   [25144..25152) signatureSlot (8)
     *   variable: attestedHeader at offset0, finalizedHeader at offset1
     */
    public static LightClientUpdate decode(byte[] ssz) {
        if (ssz.length < FIXED_SIZE) {
            throw new IllegalArgumentException(
                    "LightClientUpdate requires at least " + FIXED_SIZE + " bytes, got " + ssz.length);
        }

        ByteBuffer buf = ByteBuffer.wrap(ssz).order(ByteOrder.LITTLE_ENDIAN);

        // offset to attestedHeader
        int attestedHeaderOffset = buf.getInt();  // pos 4

        // nextSyncCommittee (24624B)
        byte[] nextSyncCommitteeBytes = new byte[SyncCommittee.ENCODED_SIZE];
        buf.get(nextSyncCommitteeBytes);  // pos 24628
        SyncCommittee nextSyncCommittee = SyncCommittee.decode(nextSyncCommitteeBytes);

        // nextSyncCommitteeBranch (5 * 32B = 160B)
        byte[][] nextSyncCommitteeBranch = new byte[5][32];
        for (int i = 0; i < 5; i++) {
            buf.get(nextSyncCommitteeBranch[i]);  // pos 24788
        }

        // offset to finalizedHeader
        int finalizedHeaderOffset = buf.getInt();  // pos 24792

        // finalityBranch (6 * 32B = 192B)
        byte[][] finalityBranch = new byte[6][32];
        for (int i = 0; i < 6; i++) {
            buf.get(finalityBranch[i]);  // pos 24984
        }

        // syncAggregate (160B)
        byte[] syncAggregateBytes = new byte[160];
        buf.get(syncAggregateBytes);  // pos 25144
        SyncAggregate syncAggregate = SyncAggregate.decode(syncAggregateBytes);

        // signatureSlot (8B)
        long signatureSlot = buf.getLong();  // pos 25152

        // Decode variable-length attestedHeader
        if (attestedHeaderOffset < FIXED_SIZE || attestedHeaderOffset > ssz.length) {
            throw new IllegalArgumentException("Invalid attestedHeader offset: " + attestedHeaderOffset);
        }
        // attestedHeader ends where finalizedHeader begins (or end of ssz if same)
        byte[] attestedHeaderBytes;
        if (finalizedHeaderOffset > attestedHeaderOffset && finalizedHeaderOffset <= ssz.length) {
            attestedHeaderBytes = Arrays.copyOfRange(ssz, attestedHeaderOffset, finalizedHeaderOffset);
        } else {
            attestedHeaderBytes = Arrays.copyOfRange(ssz, attestedHeaderOffset, ssz.length);
        }
        LightClientHeader attestedHeader = LightClientHeader.decode(attestedHeaderBytes);

        // Decode variable-length finalizedHeader
        if (finalizedHeaderOffset < FIXED_SIZE || finalizedHeaderOffset > ssz.length) {
            throw new IllegalArgumentException("Invalid finalizedHeader offset: " + finalizedHeaderOffset);
        }
        byte[] finalizedHeaderBytes = Arrays.copyOfRange(ssz, finalizedHeaderOffset, ssz.length);
        LightClientHeader finalizedHeader = LightClientHeader.decode(finalizedHeaderBytes);

        return new LightClientUpdate(
                attestedHeader,
                nextSyncCommittee,
                nextSyncCommitteeBranch,
                finalizedHeader,
                finalityBranch,
                syncAggregate,
                signatureSlot
        );
    }

    public LightClientHeader attestedHeader() { return attestedHeader; }
    public SyncCommittee nextSyncCommittee() { return nextSyncCommittee; }
    public byte[][] nextSyncCommitteeBranch() { return nextSyncCommitteeBranch; }
    public LightClientHeader finalizedHeader() { return finalizedHeader; }
    public byte[][] finalityBranch() { return finalityBranch; }
    public SyncAggregate syncAggregate() { return syncAggregate; }
    public long signatureSlot() { return signatureSlot; }
}
