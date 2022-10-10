package com.hedera.hashgraph.web.impl.http2.frames;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FrameTest {

    // TODO Turn into a parameterized test to validate all flag combinations
    @Test
    void createFrameWithFirstFlagSet() {
        final var frame = new TestFrame(0, FrameType.SETTINGS, (byte) 0b1000_0000, 0);
        assertTrue(frame.isFirstFlagSet());
        assertFalse(frame.isSecondFlagSet());
        assertFalse(frame.isThirdFlagSet());
        assertFalse(frame.isFourthFlagSet());
        assertFalse(frame.isFifthFlagSet());
        assertFalse(frame.isSixthFlagSet());
        assertFalse(frame.isSeventhFlagSet());
        assertFalse(frame.isEighthFlagSet());
    }

    private static final class TestFrame extends Frame {
        public TestFrame(int length, FrameType type, byte flags, int streamId) {
            super(length, type, flags, streamId);
        }
    }
}
