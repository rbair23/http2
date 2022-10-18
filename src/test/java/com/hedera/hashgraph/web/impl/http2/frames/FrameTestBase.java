package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import org.junit.jupiter.api.BeforeEach;

import java.util.Arrays;
import java.util.Random;

class FrameTestBase {
    private static final Random RAND = new Random(92929);
    protected InputBuffer inputBuffer;
    protected OutputBuffer outputBuffer;

    @BeforeEach
    void setUp() {
        inputBuffer = new InputBuffer(1024 * 1024);
        outputBuffer = new OutputBuffer(1024 * 1024);
    }

    void fillInputBuffer() {
        fillInputBuffer(inputBuffer, outputBuffer);
    }

    protected void fillInputBuffer(InputBuffer inputBuffer, OutputBuffer outputBuffer) {
        final var out = outputBuffer.getBuffer();
        out.flip();
        final var in = inputBuffer.getBuffer();
        in.limit(in.position() + out.limit());
        in.put(out);
        out.clear();
        in.flip();
    }

    protected static long randomLong() {
        return RAND.nextLong();
    }

    /**
     * Generates some random bytes
     *
     * @param length The number of bytes to generate.
     * @return Some random bytes.
     */
    protected static byte[] randomBytes(int length) {
        final byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) RAND.nextInt();
        }
        return data;
    }

    protected static Object corrupt(Random rand, byte[] original, int position) {
        final var corrupted = Arrays.copyOf(original, original.length);
        final var originalValue = original[position];
        var corruptedValue = originalValue;
        while (corruptedValue == originalValue) {
            corruptedValue = (byte) rand.nextInt();
        }
        corrupted[position] = corruptedValue;
        return corrupted;
    }
}
