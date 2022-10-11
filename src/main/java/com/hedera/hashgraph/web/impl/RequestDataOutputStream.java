package com.hedera.hashgraph.web.impl;

import java.io.OutputStream;
import java.util.Objects;

public final class RequestDataOutputStream extends OutputStream {
    private byte[] buf;
    private int count;

    public RequestDataOutputStream(Dispatcher.RequestData requestData) {
        this.buf = Objects.requireNonNull(requestData.getData());
        this.count = 0;
    }

    public int getCount() {
        return count;
    }

    @Override
    public synchronized void write(int b) {
        buf[count] = (byte) b;
        count += 1;
    }

    @Override
    public synchronized void write(byte b[], int off, int len) {
        Objects.checkFromIndexSize(off, len, b.length);
        System.arraycopy(b, off, buf, count, len);
        count += len;
    }
}