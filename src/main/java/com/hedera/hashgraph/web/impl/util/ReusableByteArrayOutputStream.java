package com.hedera.hashgraph.web.impl.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.BufferOverflowException;

public final class ReusableByteArrayOutputStream extends OutputStream {
    private byte[] buf;
    private int count = 0;

    public ReusableByteArrayOutputStream(byte[] buf) {
        this.buf = buf;
    }

    public void reuse() {
        count = 0;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (len + count > buf.length) {
            throw new BufferOverflowException();
        }

        System.arraycopy(b, off, buf, count, len);
        count += len;
    }

    @Override
    public void write(int b) throws IOException {
        buf[count++] = (byte) b;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public int size() {
        return count;
    }
}
