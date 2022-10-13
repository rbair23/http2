package com.hedera.hashgraph.web.impl;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Manages the set of all open connections on a single {@link ServerSocketChannel}. Listens to "accept"
 * and "read ready" NIO events.
 */
public final class ChannelManager implements AutoCloseable {
    /**
     * This is the channel we will be listening for connections on
     */
    private final ServerSocketChannel ssc;

    /**
     * NIO Selector for listening to "accept" and "read" events of connections on the {@link #ssc}.
     */
    private final Selector selector;

    /**
     * This key is used to look up "accept" events from the {@link #ssc}, and to close down the
     * socket when we're done.
     */
    private final SelectionKey acceptKey;

    /**
     * Indicates whether the TCP socket option TCP_NODELAY should be set on new connections.
     */
    private final boolean noDelay;

    /**
     * Flag set by the {@link #close()} method, generally by a different thread, to interrupt any
     * processing this class may be doing, and to interrupt the {@link #selector}.
     */
    private volatile boolean shutdown = false;

    /**
     * Creates a new instance.
     *
     * @param ssc The {@link ServerSocketChannel} to listen to. This channel must be bound already,
     *            and must not be null.
     * @param noDelay Indicates whether the TCP socket option TCP_NODELAY should be set on new connections.
     * @throws IOException If we are unable to open a selector or register it with the channel.
     */
    public ChannelManager(final ServerSocketChannel ssc, final boolean noDelay) throws IOException {
        this.ssc = Objects.requireNonNull(ssc);

        // Create the listener key for listening to new connection "accept" events
        this.selector = Selector.open();
        this.acceptKey = ssc.register(selector, SelectionKey.OP_ACCEPT);
        this.noDelay = noDelay;
    }

    /**
     * Threadsafe method to terminate any processing being done by this class and interrupt
     * the {@link #checkConnections(Duration, AtomicInteger, Predicate)} method, if it is blocking.
     */
    public void close() {
        this.shutdown = true;

        // Call the selector just to process the canceled keys (see ServerImpl.java)
        try {
            selector.wakeup();
            selector.selectNow();
            selector.close();
        } catch (IOException e) {
            System.err.println("Failed to cleanly close. Probably not fatal, but probably bad?");
            e.printStackTrace();
        }
    }

    /**
     * Iterates over all connections which are either new ("accept") or have data available for reading. For each
     * connection that is new, begins listening to that connection for "read". For each connection with data ready
     * for reading, the {@code onRead} lambda will be called.
     * <p>
     * If there are no connections, or all connections are idle, then the method will block until the {@code timeout}
     * has expired. This blocking operation may be interrupted with {@link Thread#interrupt()}. Calling the
     * {@link #close()} method will also interrupt this blocking operation, but should only be used when you are
     * done with this instance.
     *
     * @param timeout Specifies the duration of time to block while waiting for events on idle connections.
     * @param availableConnectionCount The number of available connections that can still be made
     * @param onRead A callback invoked for each {@link SelectionKey} that has data ready to be read
     */
    public void checkConnections(
            final Duration timeout,
            final AtomicInteger availableConnectionCount,
            final Predicate<SelectionKey> onRead) throws IOException {
        // TODO does the onRead need to tell me whether data was actually read or not? I think probably it does,
        //      otherwise I maybe shouldn't remove the key from the iterator...

        // Check for available keys. This call blocks until either some keys are available,
        // or until the timeout is reached.
        int numKeysAvailable = selector.select(timeout.toMillis());

        // We found no keys, so we can just return.
        if (numKeysAvailable == 0) {
            return;
        }

        // TODO I'm worried about this. Having the accept selector and the read selector be the same
        //      and processed by the same thread may mean I can accept at most one new connection
        //      per iteration of the connections. That could slow the rate of new connections to some
        //      unacceptable level. I may need two threads, one for handling new connections and
        //      one for processing existing connections with data.
        // Get the keys. They may be of two kinds. The set may contain the "acceptKey" indicating
        // that there is a new connection. Or it may contain one or more other keys that correspond
        // to a particular connection that has data to be read. For each of these, simply call the
        // onRead lambda with the key.
        final var keys = selector.selectedKeys();
        final var itr = keys.iterator();
        while (itr.hasNext() && !shutdown) {
            final var key = itr.next();

            // If the key in the set is the "acceptKey", then we have a new connection to accept.
            // To accept a connection, we will need to get the socket channel
            if (key == acceptKey && availableConnectionCount.get() > 0) {
                itr.remove();
                accept();
            } else if (key.isReadable()) {
                if (onRead.test(key)) {
                    itr.remove();
                }
            }
        }
    }

    /**
     * Accepts a new socket connection, registering to listen to read events on it.
     */
    private void accept() {
        try {
            System.out.println("New connection accepted");
            // Go ahead and accept the socket channel and configure it
            // TODO Make sure we have a configured upper limit on number of active connections
            // and keep track of that information
            final var socketChannel = ssc.accept();
            if (socketChannel != null) {
                socketChannel.socket().setTcpNoDelay(noDelay);
                socketChannel.configureBlocking(false);
                socketChannel.register(selector, SelectionKey.OP_READ);
            }
        } catch (ClosedChannelException e) {
            System.err.println("Channel Closed Prematurely. Might be OK. Not Sure if we should even log it.");
            e.printStackTrace();
        } catch (IOException e) {
            System.err.println("Something else went wrong. Might also be OK. Not sure.");
            e.printStackTrace();
        }
    }
}
