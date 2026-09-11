package com.jaeckel.ethp2p.core.consensus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Rust twin: {@code rust/myotis-consensus/src/fork.rs} unit tests. */
class ForkScheduleTest {

    private static final byte[] A = {0x05, 0, 0, 0};
    private static final byte[] B = {0x06, 0, 0, 0};
    private static final byte[] C = {0x07, 0, 0, 0};

    private static ForkSchedule three() {
        return ForkSchedule.of(32,
                new ForkSchedule.Fork(0, A), new ForkSchedule.Fork(10, B), new ForkSchedule.Fork(20, C));
    }

    @Test
    void versionAtEpochPicksLatestActivated() {
        ForkSchedule s = three();
        assertArrayEquals(A, s.versionAtEpoch(0));
        assertArrayEquals(A, s.versionAtEpoch(9));
        assertArrayEquals(B, s.versionAtEpoch(10));
        assertArrayEquals(B, s.versionAtEpoch(19));
        assertArrayEquals(C, s.versionAtEpoch(20));
        assertArrayEquals(C, s.versionAtEpoch(Long.MAX_VALUE));
    }

    /** The spec's max(signature_slot, 1) - 1: the FIRST slot of the activation epoch still signs OLD. */
    @Test
    void signatureSlotBoundaryIsOffByOnePerSpec() {
        ForkSchedule s = three();
        assertArrayEquals(A, s.versionForSignatureSlot(0));
        assertArrayEquals(A, s.versionForSignatureSlot(1));
        assertArrayEquals(A, s.versionForSignatureSlot(320)); // slot 319 -> epoch 9
        assertArrayEquals(B, s.versionForSignatureSlot(321)); // slot 320 -> epoch 10
        assertArrayEquals(B, s.versionForSignatureSlot(640));
        assertArrayEquals(C, s.versionForSignatureSlot(641));
    }

    @Test
    void slotsPerEpochIsPartOfTheSchedule() {
        ForkSchedule s = ForkSchedule.of(16, new ForkSchedule.Fork(0, A), new ForkSchedule.Fork(10, B));
        assertArrayEquals(A, s.versionForSignatureSlot(160)); // slot 159 -> epoch 9
        assertArrayEquals(B, s.versionForSignatureSlot(161)); // slot 160 -> epoch 10
    }

    @Test
    void currentAndPriorAreTheTail() {
        ForkSchedule s = three();
        assertArrayEquals(C, s.current());
        assertArrayEquals(B, s.prior());
        ForkSchedule one = ForkSchedule.single(A);
        assertArrayEquals(A, one.current());
        assertNull(one.prior());
        assertArrayEquals(A, one.versionForSignatureSlot(Long.MAX_VALUE));
    }

    @Test
    void forkLiteralIsBigEndian() {
        assertArrayEquals(new byte[]{0x06, 0x00, 0x00, 0x64}, ForkSchedule.fork(1, 0x06000064).version());
        assertArrayEquals(new byte[]{(byte) 0x90, 0x00, 0x00, 0x75}, ForkSchedule.fork(1, 0x90000075).version());
    }

    @Test
    void valueSemantics() {
        assertEquals(three(), three());
        assertEquals(three().hashCode(), three().hashCode());
        assertNotEquals(three(), ForkSchedule.of(16,
                new ForkSchedule.Fork(0, A), new ForkSchedule.Fork(10, B), new ForkSchedule.Fork(20, C)));
        assertEquals("ForkSchedule{slotsPerEpoch=32, [0:05000000, 10:06000000, 20:07000000]}", three().toString());
    }

    @Test
    void rejectsMalformedSchedules() {
        assertThrows(IllegalArgumentException.class, () -> ForkSchedule.of(32));
        assertThrows(IllegalArgumentException.class, () -> ForkSchedule.of(32, new ForkSchedule.Fork(5, A)));
        assertThrows(IllegalArgumentException.class, () -> ForkSchedule.of(32,
                new ForkSchedule.Fork(0, A), new ForkSchedule.Fork(20, B), new ForkSchedule.Fork(10, C)));
        assertThrows(IllegalArgumentException.class, () -> ForkSchedule.of(32,
                new ForkSchedule.Fork(0, A), new ForkSchedule.Fork(10, B), new ForkSchedule.Fork(10, C)));
        assertThrows(IllegalArgumentException.class, () -> ForkSchedule.of(0, new ForkSchedule.Fork(0, A)));
        assertThrows(IllegalArgumentException.class, () -> new ForkSchedule.Fork(0, new byte[3]));
        assertThrows(IllegalArgumentException.class, () -> new ForkSchedule.Fork(-1, A));
    }

    @Test
    void forkVersionIsDefensivelyCopied() {
        byte[] v = {1, 2, 3, 4};
        ForkSchedule.Fork f = new ForkSchedule.Fork(0, v);
        v[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3, 4}, f.version());
        f.version()[0] = 9;
        assertArrayEquals(new byte[]{1, 2, 3, 4}, f.version());
    }
}
