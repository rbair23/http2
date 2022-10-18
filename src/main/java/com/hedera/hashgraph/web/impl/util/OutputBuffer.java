package com.hedera.hashgraph.web.impl.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;

/**
 * An OutputStream around a ByteBuffer that is reusable and has helpful write methods.
 */
public class OutputBuffer extends OutputStream {
    private final Runnable onCloseCallback;
    private final Runnable onDataFullCallback;

    // The buffer.position() marks the next position to write into the buffer,
    // while buffer.limit() marks the last possible byte to fill, so the limit
    // is set to buffer.capacity().
    private final ByteBuffer buffer;

    public OutputBuffer(int size) {
        this(size, null, null);
    }

    public OutputBuffer(int size, Runnable onCloseCallback, Runnable onDataFullCallback) {
        this.buffer = ByteBuffer.allocate(size);
        this.onCloseCallback = onCloseCallback;
        this.onDataFullCallback = onDataFullCallback;
    }

    /**
     * Either calls onDataFullCallback if it is set. onDataFullCallback is expected to consume all the data so the
     * buffer can be reused. If onDataFullCallback is not set then a {@link BufferOverflowException} is thrown.
     */
    private void dataFull() throws BufferOverflowException {
        // TODO Throw if closed
        if (onDataFullCallback != null) {
            onDataFullCallback.run();
            this.reset();
        } else {
            throw new BufferOverflowException();
        }
    }

    /**
     */
    private void fullIfNotRemaining(int numBytes) {
        if (buffer.remaining() < numBytes) {
            dataFull();
        }
    }

    public OutputBuffer reset() {
        // TODO Throw if closed
        buffer.clear();
        return this;
    }

    public ByteBuffer getBuffer() {
        // TODO Throw if closed
        return buffer;
    }

    @Override
    public void close() throws IOException {
        super.close();
        if (onCloseCallback != null) onCloseCallback.run();
    }

    /**
     * Get the number of bytes written
     * TODO if we get to the end of the buffer and it is reset, this number is no longer
     *      the number of bytes written, only the number of bytes available in the buffer.
     */
    public int size() {
        // TODO Throw if closed
        return buffer.position();
    }

    @Override
    public void write(int b) {
        fullIfNotRemaining(1);
        buffer.put((byte)b);
    }

    @Override
    public void write(byte[] b, int off, int len) {
        fullIfNotRemaining(len);
        buffer.put(b,off,len);
    }

    public void write(OutputBuffer anotherBuffer) {
        fullIfNotRemaining(anotherBuffer.size());
        buffer.put(anotherBuffer.buffer.flip());
    }

    public void write(String string) {
        fullIfNotRemaining(string.length());
        // write a string with no copies - TODO do we need to handle multi byte chars?
        for (int i = 0; i < string.length(); i++) {
            buffer.put((byte)string.charAt(i));
        }
    }

    public void write24BitInteger(int value) {
        fullIfNotRemaining(3);
        buffer.put((byte) ((value >>> 16) & 0xFF));
        buffer.put((byte) ((value >>> 8) & 0xFF));
        buffer.put((byte) (value & 0XFF));
    }

    public void writeByte(int value) {
       fullIfNotRemaining(1);
        buffer.put((byte) value);
    }

    public void write32BitInteger(int value) {
        fullIfNotRemaining(Integer.BYTES);
        buffer.putInt(value);
    }

    public void write16BigInteger(int value) {
        fullIfNotRemaining(2);
        buffer.put((byte) ((value >>> 8) & 0xFF));
        buffer.put((byte) (value & 0XFF));
    }

    public void write32BitUnsignedInteger(long value) {
        fullIfNotRemaining(Integer.BYTES);
        buffer.put((byte) ((value >>> 24) & 0xFF));
        buffer.put((byte) ((value >>> 16) & 0xFF));
        buffer.put((byte) ((value >>> 8) & 0xFF));
        buffer.put((byte) (value & 0XFF));
    }

    public void write64BitLong(long pingData) {
        buffer.putLong(pingData);
    }

    public void position(int i) {
        buffer.position(i);
    }
}
