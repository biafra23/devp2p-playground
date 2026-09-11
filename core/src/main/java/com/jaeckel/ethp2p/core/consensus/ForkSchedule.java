package com.jaeckel.ethp2p.core.consensus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A beacon chain's fork schedule: the append-only, ascending list of
 * {@code (activation epoch, fork version)} pairs that selects the signing domain
 * for every sync-committee signature. Java twin of the Rust
 * {@code myotis_consensus::fork::ForkSchedule}.
 *
 * <p><b>Why a schedule and not one version.</b> The spec's
 * {@code validate_light_client_update} verifies a sync aggregate under
 * {@code compute_fork_version(compute_epoch_at_slot(max(signature_slot, 1) - 1))}
 * — the fork active when the signature was produced, not the network's current
 * fork. A store walking updates across a fork boundary needs both versions; a
 * single configured value verifies one side and rejects every update on the other,
 * stalling sync at every consensus fork on every install (#295).
 *
 * <p><b>Trust posture.</b> Consensus-critical configuration with the same standing
 * as the genesis validators root: embedded, never fetched at runtime. The beacon
 * API's {@code /eth/v1/config/fork_schedule} exposes the same data and is the
 * reference for the pinned lists in {@code NetworkConfig}.
 *
 * <p>Lives in {@code :core} because both {@code :networking} (the config) and
 * {@code :consensus} (the processor) need it and neither depends on the other.
 * Android-safe: arrays and {@link List} only.
 */
public final class ForkSchedule {

    /** One scheduled fork: the epoch it activates at and its 4-byte version. */
    public record Fork(long epoch, byte[] version) {
        public Fork {
            if (epoch < 0) throw new IllegalArgumentException("fork epoch must be >= 0");
            if (version == null || version.length != 4)
                throw new IllegalArgumentException("fork version must be 4 bytes");
            version = version.clone();
        }

        /** Defensive copy — the record component is a mutable array. */
        @Override
        public byte[] version() {
            return version.clone();
        }
    }

    private final int slotsPerEpoch;
    private final List<Fork> forks;

    private ForkSchedule(int slotsPerEpoch, List<Fork> forks) {
        this.slotsPerEpoch = slotsPerEpoch;
        this.forks = forks;
    }

    /**
     * Build a schedule. Throws on: a non-positive {@code slotsPerEpoch}, an empty
     * list, a first entry not at epoch 0, or epochs that are not strictly
     * ascending. Each would silently select a wrong signing domain for some slot —
     * the "accepted and silently ignored" failure CLAUDE.md forbids for anything that
     * can change the answer — so the constructor refuses rather than defaults.
     *
     * @param slotsPerEpoch slots per epoch for THIS chain (32 on the mainnet preset,
     *                      16 on gnosis). Bundled with the schedule so it can never
     *                      be read with another chain's geometry.
     */
    public static ForkSchedule of(int slotsPerEpoch, Fork... forks) {
        if (slotsPerEpoch <= 0)
            throw new IllegalArgumentException("fork schedule: slotsPerEpoch must be positive");
        if (forks == null || forks.length == 0)
            throw new IllegalArgumentException("fork schedule: at least the genesis fork is required");
        if (forks[0].epoch() != 0)
            throw new IllegalArgumentException("fork schedule: the first entry must activate at epoch 0");
        for (int i = 1; i < forks.length; i++) {
            if (forks[i - 1].epoch() >= forks[i].epoch())
                throw new IllegalArgumentException("fork schedule: activation epochs must be strictly ascending ("
                        + forks[i - 1].epoch() + " then " + forks[i].epoch() + ")");
        }
        List<Fork> copy = new ArrayList<>(forks.length);
        for (Fork f : forks) copy.add(Objects.requireNonNull(f));
        return new ForkSchedule(slotsPerEpoch, Collections.unmodifiableList(copy));
    }

    /**
     * A fork entry from the version written as the usual hex literal, e.g.
     * {@code fork(1714688, 0x06000064)} is Fulu on Gnosis — big-endian bytes
     * {@code 06 00 00 64}, exactly the spec's {@code Version} byte order.
     */
    public static Fork fork(long epoch, int version) {
        return new Fork(epoch, new byte[]{
                (byte) (version >>> 24), (byte) (version >>> 16), (byte) (version >>> 8), (byte) version});
    }

    /**
     * One version for every slot — a schedule with no boundary. For tests and for
     * replaying a corpus recorded under a single version. The slot geometry is
     * immaterial without a boundary; the mainnet preset is used so it is a real one.
     */
    public static ForkSchedule single(byte[] version) {
        return of(32, new Fork(0, version));
    }

    public int slotsPerEpoch() {
        return slotsPerEpoch;
    }

    /** The pinned entries, ascending by activation epoch; unmodifiable. */
    public List<Fork> forks() {
        return forks;
    }

    /**
     * The newest scheduled fork's version — what the legacy single
     * {@code currentForkVersion} held. Feeds the fork DIGEST (discv5 filtering,
     * Status), never signature verification.
     */
    public byte[] current() {
        return forks.get(forks.size() - 1).version();
    }

    /** The fork before {@link #current()}, or {@code null} for a one-entry schedule. */
    public byte[] prior() {
        return forks.size() >= 2 ? forks.get(forks.size() - 2).version() : null;
    }

    /**
     * {@code compute_fork_version(epoch)}: the version of the latest fork whose
     * activation epoch is {@code <= epoch}. Total — the genesis entry covers epoch 0.
     */
    public byte[] versionAtEpoch(long epoch) {
        Fork chosen = forks.get(0);
        for (Fork f : forks) {
            if (f.epoch() <= epoch) chosen = f;
            else break;
        }
        return chosen.version();
    }

    /**
     * The fork version a sync aggregate signed at {@code signatureSlot} must be
     * verified under — spec {@code validate_light_client_update}:
     * {@code compute_fork_version(compute_epoch_at_slot(max(signature_slot, 1) - 1))}.
     *
     * <p>The {@code - 1} is not a detail: the aggregate is over the block of the
     * previous slot, so a signature at the first slot of a fork's activation epoch
     * still uses the old version, and only the next slot switches.
     */
    public byte[] versionForSignatureSlot(long signatureSlot) {
        long slot = Math.max(signatureSlot, 1L) - 1L;
        return versionAtEpoch(slot / slotsPerEpoch);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ForkSchedule other)) return false;
        if (slotsPerEpoch != other.slotsPerEpoch || forks.size() != other.forks.size()) return false;
        for (int i = 0; i < forks.size(); i++) {
            if (forks.get(i).epoch() != other.forks.get(i).epoch()) return false;
            if (!java.util.Arrays.equals(forks.get(i).version(), other.forks.get(i).version())) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = slotsPerEpoch;
        for (Fork f : forks) h = 31 * h + Long.hashCode(f.epoch()) * 31 + java.util.Arrays.hashCode(f.version());
        return h;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ForkSchedule{slotsPerEpoch=").append(slotsPerEpoch).append(", [");
        for (int i = 0; i < forks.size(); i++) {
            Fork f = forks.get(i);
            if (i > 0) sb.append(", ");
            sb.append(f.epoch()).append(':');
            for (byte b : f.version()) sb.append(String.format("%02x", b));
        }
        return sb.append("]}").toString();
    }
}
