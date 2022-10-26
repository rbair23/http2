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
    private final Runnable onCLoseCallback;

    private OutputBuffer currentBuffer;
    private boolean isClosed = false;

    /**
     * Construct a new OutputBufferOutputStream
     *
     * @param checkoutOutputBuffer supplier of output buffers we can use
     * @param sendResponse consumer for taking filled output buffers and sending them. The buffers are not pre-flipped
     *                     before calling.
     * @param onCLoseCallback call back for when closed is called, after last buffer has been sent
     */
    public OutputBufferOutputStream(Supplier<OutputBuffer> checkoutOutputBuffer,
                                    Consumer<OutputBuffer> sendResponse,
                                    Runnable onCLoseCallback) {
        this.checkoutOutputBuffer = Objects.requireNonNull(checkoutOutputBuffer);
        this.sendResponse = Objects.requireNonNull(sendResponse);
        this.onCLoseCallback = Objects.requireNonNull(onCLoseCallback);
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
            sendResponse.accept(currentBuffer);
            onCLoseCallback.run();
        }
        isClosed = true;
    }
}
