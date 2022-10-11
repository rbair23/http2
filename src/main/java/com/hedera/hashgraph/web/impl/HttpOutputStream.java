package com.hedera.hashgraph.web.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class HttpOutputStream {
    private ByteBuffer buffer = ByteBuffer.allocate(1 << 16);

    public HttpOutputStream() {
    }

    void reset() {
        buffer.position(0);
    }

    // TODO flush is called from one thread, but everything else may be called from another, so this can be bad.
    void flush(SocketChannel channel) throws IOException {
        int length = buffer.position();
        buffer.position(0);
        channel.write(buffer.slice(0, length));
    }

    public void write24BitInteger(int value) {
        buffer.put((byte) ((value >>> 16) & 0xFF));
        buffer.put((byte) ((value >>> 8) & 0xFF));
        buffer.put((byte) (value & 0XFF));
    }

    public void writeByte(int value) {
        buffer.put((byte) value);
    }

    public void write32BitInteger(int value) {
        buffer.putInt(value);
    }

    public void write16BigInteger(int value) {
        buffer.put((byte) ((value >>> 8) & 0xFF));
        buffer.put((byte) (value & 0XFF));
    }

    public void write32BitUnsignedInteger(long value) {
        buffer.putInt((int) ((value >>> 24) & 0xFF));
        buffer.putInt((int) ((value >>> 16) & 0xFF));
        buffer.putInt((int) ((value >>> 8) & 0xFF));
        buffer.putInt((int) (value & 0XFF));
    }

    public void writeBytes(byte[] data, int offset, int length) {
        buffer.put(data, offset, length);
    }
}
