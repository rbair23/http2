package com.hedera.hashgraph.web.impl.http;

import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.SocketChannel;

// TODO needs to send data if buffer is full and needs to know
class HttpOutputStream extends OutputStream {
    private final OutputBuffer outputBuffer;
    private final SocketChannel channel;

    public HttpOutputStream(OutputBuffer outputBuffer, SocketChannel channel) {
        this.outputBuffer = outputBuffer;
        this.channel = channel;
        outputBuffer.reset();
    }
//
//    @Override
//    public void flush() throws IOException {
//        outputBuffer.sendContentsToChannel(channel);
//        outputBuffer.reset();
//    }

    @Override
    public void write(int b) throws IOException {
        outputBuffer.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        outputBuffer.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
//        outputBuffer.sendContentsToChannel(channel);
    }
}
