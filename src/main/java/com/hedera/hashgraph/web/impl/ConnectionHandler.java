package com.hedera.hashgraph.web.impl;

import com.hedera.hashgraph.web.impl.http2.Http2ProtocolHandler;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.Objects;

/**
 * Parses frames from the channel and supplies them to one or more request handlers based on the frame id.
 */
public class ConnectionHandler implements Runnable {
    private static final int MAX_FRAME_SIZE = (1 << 14) + 9;
    private static final int BUFFER_SIZE = MAX_FRAME_SIZE * 2;

    /**
     * The channel representing the connection with the client. We will read bytes from this channel
     * and write bytes to it.
     */
    private SocketChannel channel;

    public ConnectionHandler(SocketChannel channel) {
        this.channel = Objects.requireNonNull(channel);
    }

    @Override
    public void run() {
        final var readStream = new HttpInputStream(channel, BUFFER_SIZE);
        final var outStream = new HttpOutputStream();

        // Create the protocol. Really, this would happen in a totally different order because we won't
        // know the protocol until we have read some data from te stream (possibly a lot of it,
        // if we have the old deprecated HTTP/2 way of using the HTTP/1.1 headers to determine we want to upgrade
        final var http2ProtocolHandler = new Http2ProtocolHandler();
        http2ProtocolHandler.handle(readStream, outStream);

        try {
            channel.close();
        } catch (IOException e) {
            System.err.println("I have failed horribly");
            e.printStackTrace();
        }
    }

    public void shutdown() {
        try {
            channel.close();
        } catch (IOException e) {
            System.err.println("Tried to close prematurely");
            e.printStackTrace();
        }
    }
}
