package devp2p.consensus.lightclient;

/**
 * Ethereum consensus-layer constants for the light client protocol.
 */
public final class BeaconChainSpec {

    public static final int SLOTS_PER_EPOCH = 32;
    public static final int EPOCHS_PER_SYNC_COMMITTEE_PERIOD = 256;
    public static final int SLOTS_PER_SYNC_COMMITTEE_PERIOD =
            SLOTS_PER_EPOCH * EPOCHS_PER_SYNC_COMMITTEE_PERIOD; // 8192
    public static final int SYNC_COMMITTEE_SIZE = 512;
    public static final int MIN_SYNC_COMMITTEE_PARTICIPANTS = 1;
    public static final int UPDATE_TIMEOUT = SLOTS_PER_SYNC_COMMITTEE_PERIOD;

    // Domain types (4 bytes each)
    public static final byte[] DOMAIN_SYNC_COMMITTEE = {0x07, 0x00, 0x00, 0x00};

    // Generalized index for execution payload in BeaconBlockBody (Capella+)
    // Body tree: depth 4 from body root, index 25 in the generalized tree
    public static final int EXECUTION_PAYLOAD_GINDEX = 25;
    public static final int EXECUTION_PAYLOAD_DEPTH = 4;

    // Generalized index for current sync committee in BeaconState
    // State tree: depth 5, index 54
    public static final int CURRENT_SYNC_COMMITTEE_GINDEX = 54;
    public static final int CURRENT_SYNC_COMMITTEE_DEPTH = 5;

    // Generalized index for finalized checkpoint root in BeaconState
    // State tree: depth 6, index 105
    public static final int FINALIZED_ROOT_GINDEX = 105;
    public static final int FINALIZED_ROOT_DEPTH = 6;

    private BeaconChainSpec() {}

    /**
     * Compute the sync committee period for a given slot.
     */
    public static long computeSyncCommitteePeriod(long slot) {
        return slot / SLOTS_PER_SYNC_COMMITTEE_PERIOD;
    }
}
