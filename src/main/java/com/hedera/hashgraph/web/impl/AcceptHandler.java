package com.hedera.hashgraph.web.impl;

import java.io.IOException;
import java.nio.channels.*;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Handles incoming "accept" TCP connections, and holds onto the socket until it has data ready to be read.
 * At that point, it converts the socket to a blocking read operation, and passes it into a buffer for connections...
 */
public class AcceptHandler implements Runnable {
    private volatile boolean quit;

    private final ServerSocketChannel ssc;
    private final Executor connectionPool;
    private final Selector selector;
    private final SelectionKey listenerKey;
    private final Duration timeout;

    public AcceptHandler(
            final ServerSocketChannel ssc,
            final SelectionKey listenerKey,
            final Selector selector,
            final Duration selectorTimeout,
            final Executor connectionPool) {
        this.ssc = Objects.requireNonNull(ssc);
        this.listenerKey = Objects.requireNonNull(listenerKey);
        this.connectionPool = Objects.requireNonNull(connectionPool);
        this.selector = Objects.requireNonNull(selector);
        this.timeout = Objects.requireNonNull(selectorTimeout);
    }

    @Override
    public void run() {
        while (!quit) {
            try {
                selector.select(timeout.toMillis());
            } catch (IOException e) {
                System.err.println("Something went wrong while trying to prime the selector");
                e.printStackTrace();
            }

            // Ask the selector for all the selection keys. They may contain a combination of the
            // "listener" key (which corresponds to "accept" events), or other keys corresponding
            // to socket channels previously opened, but waiting for their first connection.
            final var keys = selector.selectedKeys();
            final var itr = keys.iterator();
            while (itr.hasNext()) {
                final var key = itr.next();
                itr.remove();

                // If the key in the set is the "listener" key, then we have a new socket to accept.
                // To accept a socket, we will need to get the socket channel and create a new
                // Http2Connection with all the gory details. We'll hold onto that object until
                // data is ready to be read. At that point, we'll go ahead and submit the connection
                // to the executor for handling.
                if (key == listenerKey) {
                    accept();
                } else if (key.isReadable()) {
                    process(key);
                }
            }

            // call the selector just to process the canceled keys (see ServerImpl.java)
            try {
                selector.selectNow();
            } catch (IOException e) {
                System.err.println("Failed to select. No idea.");
                e.printStackTrace();
            }
        }

        try {
            selector.close();
        } catch (IOException e) {
            System.err.println("Couldn't close selector. Probably non-fatal...");
            e.printStackTrace();
        }
    }

    public void shutdown() {
        // Causes the checkConnections() loop to terminate, possibly "timeout" milliseconds later.
        quit = true;
    }

    private void accept() {
        // If the quit flag has been set, just skip this.
        if (quit) {
            return;
        }

        // Go ahead and accept the socket channel and configure it
        try {
            final var socketChannel = ssc.accept();
            if (socketChannel != null) {
//                // TODO Set TCP_NODELAY, if appropriate
//                if (ServerConfig.noDelay()) {
//                    chan.socket().setTcpNoDelay(true);
//                }
                socketChannel.configureBlocking(false);

                // SocketChannel keeps a reference to newKey, so I don't have to.
                final var newKey = socketChannel.register(selector, SelectionKey.OP_READ);
                final var conn = new ConnectionHandler(socketChannel);
                newKey.attach(conn);
            }
        } catch (ClosedChannelException e) {
            System.err.println("Channel Closed Prematurely. Might be OK. Not Sure if we should even log it.");
            e.printStackTrace();
        } catch (IOException e) {
            System.err.println("Something else went wrong. Might also be OK. Not sure.");
            e.printStackTrace();
        }
    }

    private void process(SelectionKey key) {
        try {
            final var conn = (ConnectionHandler) key.attachment();
            final var channel = key.channel();
            key.cancel(); // We no longer need events from this thing. We can just use the channel.
            channel.configureBlocking(true); // Switch to blocking mode for this data stream
            connectionPool.execute(conn);
        } catch (IOException e) {
            System.err.println("Not sure if I care about this either, something bogus somewhere.");
            e.printStackTrace();
        }
    }
}
