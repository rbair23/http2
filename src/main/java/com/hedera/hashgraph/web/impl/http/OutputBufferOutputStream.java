package com.hedera.hashgraph.web.impl.http;

import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Output stream that uses OutputBuffers to store the data written to it and calls a "send call back" as they get filled.
 */
class OutputBufferOutputStream extends OutputStream {
    private final Supplier<OutputBuffer> checkoutOutputBuffer;
    private final Consumer<OutputBuffer> sendResponse;
    private final Consumer<OutputBufferOutputStream> onPreCloseCallback;
    private final Runnable onCloseCallback;

    private OutputBuffer currentBuffer;
    private boolean isClosed = false;

    /**
     * Construct a new OutputBufferOutputStream
     *
     * @param checkoutOutputBuffer supplier of output buffers we can use
     * @param sendResponse consumer for taking filled output buffers and sending them. The buffers are not pre-flipped
     *                     before calling.
     * @param onPreCloseCallback call back for when closed is called before we close, can send extra data
     * @param onCloseCallback call back for when closed is called, after last buffer has been sent
     */
    public OutputBufferOutputStream(Supplier<OutputBuffer> checkoutOutputBuffer,
                                    Consumer<OutputBuffer> sendResponse,
                                    Consumer<OutputBufferOutputStream> onPreCloseCallback,
                                    Runnable onCloseCallback) {
        this.checkoutOutputBuffer = Objects.requireNonNull(checkoutOutputBuffer);
        this.sendResponse = Objects.requireNonNull(sendResponse);
        this.onPreCloseCallback = Objects.requireNonNull(onPreCloseCallback);
        this.onCloseCallback = Objects.requireNonNull(onCloseCallback);
    }

    /**
     * Send the current buffer contents and get a new buffer
     */
    @Override
    public void flush() {
        sendResponse.accept(currentBuffer);
        currentBuffer = checkoutOutputBuffer.get();
    }

    @Override
    public void write(int b) throws IOException {
        if (currentBuffer == null) {
            currentBuffer = checkoutOutputBuffer.get();
        } else if(currentBuffer.remaining() < 1) {
            sendResponse.accept(currentBuffer);
            currentBuffer = checkoutOutputBuffer.get();
        }
        currentBuffer.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (currentBuffer == null) {
            currentBuffer = checkoutOutputBuffer.get();
        } else if(currentBuffer.remaining() < len) {
            sendResponse.accept(currentBuffer);
            currentBuffer = checkoutOutputBuffer.get();
        }
        currentBuffer.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
        if (!isClosed) {
            // call back pre-close call back, so it can write any extra data
            onPreCloseCallback.accept(this);
            // send final data buffer
            sendResponse.accept(currentBuffer);
            // mark us as closed
            isClosed = true;
            // call we are closed call back
            onCloseCallback.run();
        }
    }
}
