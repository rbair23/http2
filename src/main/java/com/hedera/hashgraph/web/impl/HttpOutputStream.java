package com.hedera.hashgraph.web.impl;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Objects;

public class HttpOutputStream {
    private ByteBuffer buffer = ByteBuffer.allocate(1 << 16);

    public HttpOutputStream() {
    }

    void reset(SocketChannel channel) {
//        this.out = Objects.requireNonNull(out);
    }

    public void write24BitInteger(int value) throws IOException {
//        out.write((value >>> 16) & 0xF);
//        out.write((value >>> 8) & 0xF);
//        out.write(value & 0XF);
    }

    public void writeByte(int value) throws IOException {
//        out.write(value);
    }

    public void write32BitInteger(int value) throws IOException {
//        out.write((value >>> 24) & 0xF);
//        out.write((value >>> 16) & 0xF);
//        out.write((value >>> 8) & 0xF);
//        out.write(value & 0XF);
    }

    public void write16BigInteger(int value) throws IOException {
//        out.write((value >>> 8) & 0xF);
//        out.write(value & 0XF);
    }

    public void write32BitUnsignedInteger(long value) throws IOException {
//        out.write((int) ((value >>> 24) & 0xF));
//        out.write((int) ((value >>> 16) & 0xF));
//        out.write((int) ((value >>> 8) & 0xF));
//        out.write((int) (value & 0XF));
    }

    public void writeBytes(byte[] data, int offset, int length) throws IOException {
//        out.write(data, offset, length);
    }
}
