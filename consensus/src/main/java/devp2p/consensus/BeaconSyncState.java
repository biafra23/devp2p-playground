package devp2p.consensus;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe holder for beacon chain sync state.
 *
 * <p>Provides atomic access to the latest beacon-verified execution state root,
 * finalized slot, and optimistic slot. Uses a single {@link AtomicReference} to
 * an immutable record for lock-free reads.
 */
public class BeaconSyncState {

    private record State(long finalizedSlot, byte[] executionStateRoot, long optimisticSlot) {}

    private final AtomicReference<State> state = new AtomicReference<>(new State(0, null, 0));

    /**
     * Update the beacon sync state atomically.
     *
     * @param finalizedSlot       the latest finalized beacon slot
     * @param executionStateRoot  the execution state root from the finalized execution payload header
     * @param optimisticSlot      the latest optimistic (attested) slot
     */
    public void update(long finalizedSlot, byte[] executionStateRoot, long optimisticSlot) {
        state.set(new State(finalizedSlot, executionStateRoot, optimisticSlot));
    }

    /**
     * Returns the beacon-verified execution state root, or null if not yet synced.
     */
    public byte[] getVerifiedExecutionStateRoot() {
        return state.get().executionStateRoot();
    }

    /**
     * Returns the latest finalized beacon slot, or 0 if not yet synced.
     */
    public long getFinalizedSlot() {
        return state.get().finalizedSlot();
    }

    /**
     * Returns the latest optimistic (attested) slot, or 0 if not yet synced.
     */
    public long getOptimisticSlot() {
        return state.get().optimisticSlot();
    }

    /**
     * Returns true if the beacon sync state has been populated with at least one update.
     */
    public boolean isSynced() {
        return state.get().executionStateRoot() != null;
    }
}
