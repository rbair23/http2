package com.hedera.hashgraph.web.impl.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * An OutputStream around a ByteBuffer that is reusable and has helpful write methods.
 */
public class OutputBuffer extends OutputStream {
    private final Runnable onCloseCallback;
    private final Runnable onDataFullCallback;
    private ByteBuffer buffer;

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
        if (onDataFullCallback != null) {
            onDataFullCallback.run();
            buffer.clear();
        } else {
            throw new BufferOverflowException();
        }
    }

    public OutputBuffer reset() {
        buffer.clear();
        return this;
    }

    public ByteBuffer getBuffer() {
        return buffer;
    }

    @Override
    public void close() throws IOException {
        super.close();
        if (onCloseCallback != null) onCloseCallback.run();
    }

    /**
     * Get the number of bytes written
     */
    public int size() {
        return buffer.position() ;
    }

    /**
     * Get the total capacity of this output buffer
     */
    public int capacity() {
        return buffer.capacity() ;
    }

    @Override
    public void write(int b) {
        if (!buffer.hasRemaining()) dataFull();
        buffer.put((byte)b);
    }

    @Override
    public void write(byte b[], int off, int len) {
        if (buffer.remaining() < len) dataFull();
        buffer.put(b,off,len);
    }

    public void write(OutputBuffer anotherBuffer) {
        if (buffer.remaining() < anotherBuffer.size()) dataFull();
        buffer.put(anotherBuffer.buffer.flip());
    }

    public void write(String string) {
        if (buffer.remaining() < string.length()) dataFull();
        // write a string with no copies - TODO do we need to handle multi byte chars?
        for (int i = 0; i < string.length(); i++) {
            buffer.put((byte)string.charAt(i));
        }
    }


    // TODO flush is called from one thread, but everything else may be called from another, so this can be bad.
    public void sendContentsToChannel(SocketChannel channel) throws IOException {
        buffer.flip();
        channel.write(buffer);
    }

    public void write24BitInteger(int value) {
        if (buffer.remaining() < 3) dataFull();
        buffer.put((byte) ((value >>> 16) & 0xFF));
        buffer.put((byte) ((value >>> 8) & 0xFF));
        buffer.put((byte) (value & 0XFF));
    }

    public void writeByte(int value) {
        if (!buffer.hasRemaining()) dataFull();
        buffer.put((byte) value);
    }

    public void write32BitInteger(int value) {
        if (buffer.remaining() < Integer.BYTES) dataFull();
        buffer.putInt(value);
    }

    public void write16BigInteger(int value) {
        if (buffer.remaining() < 2) dataFull();
        buffer.put((byte) ((value >>> 8) & 0xFF));
        buffer.put((byte) (value & 0XFF));
    }

    public void write32BitUnsignedInteger(long value) {
        if (buffer.remaining() < 4) dataFull();
        buffer.putInt((int) ((value >>> 24) & 0xFF));
        buffer.putInt((int) ((value >>> 16) & 0xFF));
        buffer.putInt((int) ((value >>> 8) & 0xFF));
        buffer.putInt((int) (value & 0XFF));
    }

    public void write64BitLong(long pingData) {
        buffer.putLong(pingData);
    }
}
