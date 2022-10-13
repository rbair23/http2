package com.hedera.hashgraph.web.impl.http;

import com.hedera.hashgraph.web.impl.util.InputBuffer;

import java.io.IOException;
import java.io.InputStream;

class BodyInputStream extends InputStream {
    private final InputBuffer buffer;
    private final int contentLength;
    private int bytesRead = 0;
    private boolean isClosed = false;

    public BodyInputStream(InputBuffer buffer, int contentLength) {
        this.buffer = buffer;
        this.contentLength = contentLength;
    }

    @Override
    public void close() throws IOException {
        isClosed = true;
    }

    @Override
    public int available() throws IOException {
        return contentLength - bytesRead;
    }

    @Override
    public int read() throws IOException {
        bytesRead++;
        return buffer.readByte();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        final int bytesToRead = Math.max(len, available());
        buffer.readBytes(b, off, bytesToRead);
        bytesRead += bytesToRead;
        return bytesToRead;
    }
}
