package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.impl.HttpOutputStream;
import com.hedera.hashgraph.web.impl.http2.frames.Frame;
import com.hedera.hashgraph.web.impl.http2.frames.FrameTypes;
import com.hedera.hashgraph.web.impl.http2.frames.HeadersFrame;
import com.hedera.hashgraph.web.impl.util.RingBuffer;

import java.util.Objects;

public class Http2RequestHandler implements Runnable {
    private final RingBuffer<FrameHolder> frames = new RingBuffer<>(512, FrameHolder::new);
    private final HttpOutputStream out;
    private boolean shutdown = false;

    public Http2RequestHandler(HttpOutputStream out) {
        this.out = Objects.requireNonNull(out);
    }

    @Override
    public void run() {
        while (!shutdown) {
            // Read from the buffer to get the next frame.
            final var nextHolder = frames.poll();

            // If we didn't get a frame, do a very slight sleep and try again
            if (nextHolder == null) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException ignored) {
                }
                continue;
            }

            // Alright, we got the next frame, so we can now process it
            final var nextFrame = nextHolder.frame;
            if (nextFrame.getType() == FrameTypes.HEADERS) {
                final var headerFrame = (HeadersFrame) nextFrame;


            }
            // TODO Something with this frame...
        }
    }

    public void shutdown() {
        this.shutdown = true;
    }

    public void submit(Frame nextFrame) {
        frames.offer(holder -> holder.frame = nextFrame);
    }

    private static final class FrameHolder {
        private Frame frame;

        public void reset(Frame frame) {
            this.frame = frame;
        }
    }
}
