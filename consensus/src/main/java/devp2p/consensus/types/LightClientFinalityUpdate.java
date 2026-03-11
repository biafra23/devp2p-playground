package devp2p.consensus.types;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * SSZ container: LightClientFinalityUpdate
 *
 * Fields (in order):
 *   attestedHeader   — LightClientHeader (variable)
 *   finalizedHeader  — LightClientHeader (variable)
 *   finalityBranch   — Vector[Bytes32, 6] (192B, fixed inline)
 *   syncAggregate    — SyncAggregate (160B, fixed inline)
 *   signatureSlot    — uint64 (8B, fixed inline)
 *
 * SSZ fixed part:
 *   4B  offset to attestedHeader
 *   4B  offset to finalizedHeader
 *   192B  finalityBranch
 *   160B  syncAggregate
 *   8B  signatureSlot
 *   Total fixed: 4 + 4 + 192 + 160 + 8 = 368 bytes
 */
public final class LightClientFinalityUpdate {

    public static final int FIXED_SIZE = 4 + 4 + 6 * 32 + 160 + 8; // 368

    private final LightClientHeader attestedHeader;
    private final LightClientHeader finalizedHeader;
    private final byte[][] finalityBranch; // 6 x 32
    private final SyncAggregate syncAggregate;
    private final long signatureSlot;

    public LightClientFinalityUpdate(
            LightClientHeader attestedHeader,
            LightClientHeader finalizedHeader,
            byte[][] finalityBranch,
            SyncAggregate syncAggregate,
            long signatureSlot
    ) {
        if (finalityBranch.length != 6) throw new IllegalArgumentException("finalityBranch must have 6 nodes");
        this.attestedHeader = attestedHeader;
        this.finalizedHeader = finalizedHeader;
        this.finalityBranch = finalityBranch;
        this.syncAggregate = syncAggregate;
        this.signatureSlot = signatureSlot;
    }

    /**
     * Decode a LightClientFinalityUpdate from SSZ bytes.
     *
     * Fixed layout:
     *   [0..4)     offset to attestedHeader
     *   [4..8)     offset to finalizedHeader
     *   [8..200)   finalityBranch (6 * 32B = 192B)
     *   [200..360) syncAggregate (160B)
     *   [360..368) signatureSlot (8B)
     *   variable: attestedHeader at offset0, finalizedHeader at offset1
     */
    public static LightClientFinalityUpdate decode(byte[] ssz) {
        if (ssz.length < FIXED_SIZE) {
            throw new IllegalArgumentException(
                    "LightClientFinalityUpdate requires at least " + FIXED_SIZE + " bytes, got " + ssz.length);
        }

        ByteBuffer buf = ByteBuffer.wrap(ssz).order(ByteOrder.LITTLE_ENDIAN);

        int attestedHeaderOffset = buf.getInt();   // pos 4
        int finalizedHeaderOffset = buf.getInt();  // pos 8

        byte[][] finalityBranch = new byte[6][32];
        for (int i = 0; i < 6; i++) {
            buf.get(finalityBranch[i]);  // pos 200
        }

        byte[] syncAggregateBytes = new byte[160];
        buf.get(syncAggregateBytes);  // pos 360
        SyncAggregate syncAggregate = SyncAggregate.decode(syncAggregateBytes);

        long signatureSlot = buf.getLong();  // pos 368

        // Validate offsets
        if (attestedHeaderOffset < FIXED_SIZE || attestedHeaderOffset > ssz.length) {
            throw new IllegalArgumentException("Invalid attestedHeader offset: " + attestedHeaderOffset);
        }
        if (finalizedHeaderOffset < FIXED_SIZE || finalizedHeaderOffset > ssz.length) {
            throw new IllegalArgumentException("Invalid finalizedHeader offset: " + finalizedHeaderOffset);
        }

        // Determine the end boundary for each variable field
        // They are ordered: attestedHeader first, then finalizedHeader (by convention of offset order)
        int attestedEnd;
        int finalizedEnd;
        if (attestedHeaderOffset < finalizedHeaderOffset) {
            attestedEnd = finalizedHeaderOffset;
            finalizedEnd = ssz.length;
        } else {
            finalizedEnd = attestedHeaderOffset;
            attestedEnd = ssz.length;
        }

        byte[] attestedBytes = Arrays.copyOfRange(ssz, attestedHeaderOffset, attestedEnd);
        LightClientHeader attestedHeader = LightClientHeader.decode(attestedBytes);

        byte[] finalizedBytes = Arrays.copyOfRange(ssz, finalizedHeaderOffset, finalizedEnd);
        LightClientHeader finalizedHeader = LightClientHeader.decode(finalizedBytes);

        return new LightClientFinalityUpdate(attestedHeader, finalizedHeader, finalityBranch, syncAggregate, signatureSlot);
    }

    public LightClientHeader attestedHeader() { return attestedHeader; }
    public LightClientHeader finalizedHeader() { return finalizedHeader; }
    public byte[][] finalityBranch() { return finalityBranch; }
    public SyncAggregate syncAggregate() { return syncAggregate; }
    public long signatureSlot() { return signatureSlot; }
}
