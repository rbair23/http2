package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import org.junit.jupiter.api.BeforeEach;

import java.util.Random;

class FrameTestBase {
    private Random rand = new Random(92929);
    protected InputBuffer inputBuffer;
    protected OutputBuffer outputBuffer;

    @BeforeEach
    void setUp() {
        inputBuffer = new InputBuffer(1024 * 1024);
        outputBuffer = new OutputBuffer(1024 * 1024);
    }

    void fillInputBuffer() {
        final var out = outputBuffer.getBuffer();
        out.flip();
        final var in = inputBuffer.getBuffer();
        in.limit(in.position() + out.limit());
        in.put(out);
        out.clear();
        in.flip();
    }

    protected long randomLong() {
        return rand.nextLong();
    }
}
