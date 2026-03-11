package devp2p.consensus.lightclient;

import devp2p.consensus.ssz.SszUtil;
import devp2p.consensus.types.LightClientFinalityUpdate;
import devp2p.consensus.types.LightClientUpdate;
import devp2p.consensus.types.SyncCommittee;

/**
 * Processes light client updates against a {@link LightClientStore}.
 *
 * <p>Validates sync aggregate signatures and Merkle inclusion proofs before
 * advancing the finalized and optimistic headers in the store.
 */
public class LightClientProcessor {

    private final LightClientStore store;
    private final byte[] forkVersion;
    private final byte[] genesisValidatorsRoot;

    public LightClientProcessor(LightClientStore store, byte[] forkVersion, byte[] genesisValidatorsRoot) {
        this.store = store;
        this.forkVersion = forkVersion;
        this.genesisValidatorsRoot = genesisValidatorsRoot;
    }

    /**
     * Process a {@link LightClientFinalityUpdate}.
     *
     * <ol>
     *   <li>Verify sync aggregate over the attested header.</li>
     *   <li>Verify the finality branch (proves finalizedHeader is finalized in attested state).</li>
     *   <li>Update the store's finalized and optimistic headers.</li>
     *   <li>Rotate the sync committee if a period boundary was crossed.</li>
     * </ol>
     *
     * @param update the finality update to process
     * @return true if the update was successfully applied
     */
    public boolean processFinalityUpdate(LightClientFinalityUpdate update) {
        SyncCommittee committee = store.getCurrentSyncCommittee();
        if (committee == null) {
            return false;
        }

        // Verify sync aggregate over attested header
        if (!SyncCommitteeVerifier.verify(
                update.syncAggregate(),
                committee,
                update.attestedHeader().beacon(),
                forkVersion,
                genesisValidatorsRoot)) {
            return false;
        }

        // Verify finality branch: proves finalizedHeader.beacon is finalized in attestedHeader's state
        if (!SszUtil.verifyMerkleBranch(
                update.finalizedHeader().beacon().hashTreeRoot(),
                update.finalityBranch(),
                BeaconChainSpec.FINALIZED_ROOT_DEPTH,
                BeaconChainSpec.FINALIZED_ROOT_GINDEX,
                update.attestedHeader().beacon().stateRoot())) {
            return false;
        }

        long finalizedSlot = update.finalizedHeader().beacon().slot();
        store.updateFinalized(update.finalizedHeader(), finalizedSlot);
        store.updateOptimistic(update.attestedHeader(), update.signatureSlot());

        // Rotate sync committee if we crossed a period boundary
        store.applyNextSyncCommitteeWhenPeriodChanges(finalizedSlot);

        return true;
    }

    /**
     * Process a {@link LightClientUpdate} (which may carry the next sync committee).
     *
     * <ol>
     *   <li>Verify sync aggregate over the attested header.</li>
     *   <li>Verify the finality branch.</li>
     *   <li>If a next sync committee is provided, verify its branch and store it.</li>
     *   <li>Update the store's finalized and optimistic headers.</li>
     *   <li>Rotate the sync committee if a period boundary was crossed.</li>
     * </ol>
     *
     * @param update the update to process
     * @return true if the update was successfully applied
     */
    public boolean processUpdate(LightClientUpdate update) {
        SyncCommittee committee = store.getCurrentSyncCommittee();
        if (committee == null) {
            return false;
        }

        // Verify sync aggregate over attested header
        if (!SyncCommitteeVerifier.verify(
                update.syncAggregate(),
                committee,
                update.attestedHeader().beacon(),
                forkVersion,
                genesisValidatorsRoot)) {
            return false;
        }

        // Verify finality branch
        if (!SszUtil.verifyMerkleBranch(
                update.finalizedHeader().beacon().hashTreeRoot(),
                update.finalityBranch(),
                BeaconChainSpec.FINALIZED_ROOT_DEPTH,
                BeaconChainSpec.FINALIZED_ROOT_GINDEX,
                update.attestedHeader().beacon().stateRoot())) {
            return false;
        }

        // Verify and store next sync committee if present
        SyncCommittee nextSyncCommittee = update.nextSyncCommittee();
        if (nextSyncCommittee != null && store.getNextSyncCommittee() == null) {
            // Verify the next sync committee branch against the attested state
            if (!SszUtil.verifyMerkleBranch(
                    nextSyncCommittee.hashTreeRoot(),
                    update.nextSyncCommitteeBranch(),
                    BeaconChainSpec.CURRENT_SYNC_COMMITTEE_DEPTH,
                    BeaconChainSpec.CURRENT_SYNC_COMMITTEE_GINDEX,
                    update.attestedHeader().beacon().stateRoot())) {
                return false;
            }
            store.updateNextSyncCommittee(nextSyncCommittee);
        }

        long finalizedSlot = update.finalizedHeader().beacon().slot();
        store.updateFinalized(update.finalizedHeader(), finalizedSlot);
        store.updateOptimistic(update.attestedHeader(), update.signatureSlot());

        // Rotate sync committee if we crossed a period boundary
        store.applyNextSyncCommitteeWhenPeriodChanges(finalizedSlot);

        return true;
    }

    public LightClientStore getStore() {
        return store;
    }
}
