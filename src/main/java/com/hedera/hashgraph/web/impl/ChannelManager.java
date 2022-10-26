package com.hedera.hashgraph.web.impl;

import com.hedera.hashgraph.web.HttpVersion;
import com.hedera.hashgraph.web.WebServerConfig;
import com.hedera.hashgraph.web.impl.http.Http1ConnectionContext;
import com.hedera.hashgraph.web.impl.http2.Http2ConnectionImpl;
import com.hedera.hashgraph.web.impl.session.ConnectionContext;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;

import java.io.IOException;
import java.nio.channels.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles all networking connections with the clients and reading/writing data to those clients. Also, the logic for
 * processing those incoming request bytes to turn them into
 * {@link com.hedera.hashgraph.web.WebRequest}s, and the {@link Dispatcher} for dispatching
 * {@link com.hedera.hashgraph.web.WebRequest}s.
 * <p>
 * When closed, all associated classes will also be closed, and any in-flight requests will be asynchronously canceled
 * (some may still complete, but many will be canceled).
 */
public final class ChannelManager implements Runnable, AutoCloseable {
    /**
     * The time we want to allow to block waiting for connections or new data from connections before giving up if all
     * connections are idle. This is low enough so when a server is stopped, it stops relatively quickly, but long
     * enough to keep us from a horrible busy loop consuming the CPU needlessly.
     */
    private static final Duration DEFAULT_CHANNEL_TIMEOUT = Duration.ofMillis(500);

    /**
     * The maximum number of connections we iterate over checking thier well-being on each loop
     */
    private static final int MAX_CONNECTIONS_TO_CHECK_PER_LOOP = 10;

    /**
     * This is the channel we will be listening for connections on
     */
    private final ServerSocketChannel serverSocketChannel;

    /**
     * NIO Selector for listening to "accept" and "read" events of connections on the {@link #serverSocketChannel}.
     */
    private final Selector selector;

    /**
     * This key is used to look up "accept" events from the {@link #serverSocketChannel}, and to close down the
     * socket when we're done.
     */
    private final SelectionKey acceptKey;

    /**
     * The web server's configuration. This will not be null.
     */
    private final WebServerConfig config;

    /**
     * The number of additional connections that can be made. There is a configurable upper limit.
     * As a new connection is made, this number is decremented. As the connection is closed,
     * the number is incremented.
     */
    private final AtomicInteger availableConnections;

    /**
     * This is a utility class used for reusing the different {@link ConnectionContext} types and subtypes.
     */
    private final ContextReuseManager contextReuseManager;

    /**
     * List of all active connection contexts
     */
    private final List<ConnectionContext> connectionContexts = new ArrayList<>();

    /**
     * Cursor for current position iterating over {@see connectionContexts} for checking them.
     */
    private int connectionContextsCursor = 0;

    /**
     * Set by the {@link #close()} method when it is time to stop the dispatcher.
     * This is read by one thread, and set by another.
     */
    private volatile boolean shutdown = false;

    /**
     * Create a new instance. This instance can <b>NOT</b> be safely called from other threads,
     * except for the {@link #close()} method, which can be called from any thread.
     *
     * @param config The configuration for the web server. Cannot be null.
     * @param dispatcher The dispatcher to use for dispatching requests. Cannot be null.
     * @param serverSocketChannel The server socket channel we are managing.
     */
    public ChannelManager(
            final WebServerConfig config,
            final Dispatcher dispatcher,
            final ServerSocketChannel serverSocketChannel) throws IOException {
        this.config = Objects.requireNonNull(config);
        Objects.requireNonNull(dispatcher); // used by subclasses
        this.availableConnections = new AtomicInteger(config.maxIdleConnections());
        this.contextReuseManager = new ContextReuseManager(dispatcher, config);
        this.serverSocketChannel = Objects.requireNonNull(serverSocketChannel);

        // Create the listener key for listening to new connection "accept" events
        this.selector = Selector.open();
        this.acceptKey = this.serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
    }

    /**
     * Runs until {@link #close()} is called, or the underlying socket connection fails.
     * This method will iterate over all connections, reading data, processing the data,
     * and dispatching it to the appropriate {@link com.hedera.hashgraph.web.WebRequestHandler}.
     */
    @Override
    public void run() {
        try {
            // Continue working until we are shutdown or the socket connection fails us
            while (!shutdown) {
                checkConnections();
            }
        } catch (IOException fatal) {
            // A fatal IOException has happened. This will stop the web server. We need to publish this
            // quite loudly. I don't yet have any logging strategy in this code (OOPS), so for now I
            // am just going to have to do system.err.
            System.err.println("WEBSERVER - FATAL. Underlying socket has probably been closed on us");
            fatal.printStackTrace();
        }
    }

    /**
     * Threadsafe method to terminate any processing being done by this class as soon as possible without interrupting
     * run thread.
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
     */
    private void checkConnections() throws IOException {
        // Check for available keys. This call blocks until either some keys are available,
        // or until the timeout is reached.
        selector.select(key -> {
                // TODO I'm worried about this. Having the accept selector and the read selector be the same
                //      and processed by the same thread may mean I can accept at most one new connection
                //      per iteration of the connections. That could slow the rate of new connections to some
                //      unacceptable level. I may need two threads, one for handling new connections and
                //      one for processing existing connections with data.
                // Get the keys. They may be of two kinds. The set may contain the "acceptKey" indicating
                // that there is a new connection. Or it may contain one or more other keys that correspond
                // to a particular connection that has data to be read. For each of these, simply call the
                // onRead lambda with the key.

                // If the key in the set is the "acceptKey", then we have a new connection to accept.
                // To accept a connection, we will need to get the socket channel
                try {
                    if (!key.isValid()) {
                        System.out.println("NON VALID KEY FOUND");
                    } else if (key == acceptKey && availableConnections.get() > 0) { // handle accept
                        System.out.println("Accept new connection");
                        accept();
                    } else { // handle read/write
                        final var channel = (SocketChannel) key.channel();
                        // The channel associated with this key has data to be read. If there is
                        // an attachment, then it means we've seen this key before, so we can just
                        // reuse it. If we haven't seen it before, then we'll get a ChannelData and get
                        // it setup.
                        var connectionContext = (ConnectionContext) key.attachment();
                        // check if newly accepted channel that we have never seen before
                        if (connectionContext == null) {
                            // Always start with HTTP/1.1
                            connectionContext = contextReuseManager.checkoutHttp1ConnectionContext();
                            connectionContext.reset(channel, this::handleOnClose);
                            key.attach(connectionContext);
                            availableConnections.decrementAndGet();
                            connectionContexts.add(connectionContext);
                        }
                        // check if channel is closed
                        if (!channel.isOpen()) {
                            // we can not do anything if the channel is closed
                            connectionContext.close();
                            return;
                        }
                        // channel is open so now see if it is ready for read or write
                        // do all writes
                        if (key.isValid() && key.isWritable()) {
                            connectionContext.handleOutgoingData();
                        }
                        // do all reads
                        if (key.isValid() && key.isReadable() && !connectionContext.isClosed()) {
                            connectionContext.handleIncomingData(httpVersion -> upgradeHttpVersion(httpVersion, key));
                        }
                    }
                } catch (CancelledKeyException cancelledKeyException) {
//                cancelledKeyException.printStackTrace();
                        System.out.println("CancelledKeyException - channel has been closed");
                    }
                },
                DEFAULT_CHANNEL_TIMEOUT.toMillis());

        // check all connections are well-behaved
        for (int i = 0; i < MAX_CONNECTIONS_TO_CHECK_PER_LOOP && connectionContexts.size() > 0; i++, connectionContextsCursor ++) {
            if (connectionContextsCursor >= connectionContexts.size()) {
                connectionContextsCursor = 0;
            }
            final ConnectionContext cc = connectionContexts.get(connectionContextsCursor);
            if (cc.isTerminated() || !cc.checkClientIsWellBehaving()) {
                connectionContexts.remove(connectionContextsCursor);
                // we just changed index
                connectionContextsCursor = Math.min(0, connectionContextsCursor -1);
            }
        }
    }

    private void handleOnClose(boolean isFullyClosed, ConnectionContext closedConnectionContext) {
        connectionContexts.remove(closedConnectionContext);
        if (isFullyClosed) {
            availableConnections.incrementAndGet();
        }
    }

    /**
     * Handle HTTP version upgrades, only supports HTTP 1.1 to HTTP 2.0 for now
     *
     * @param version The version we are upgrading to
     * @param key The selection key for channel we are upgrading
     */
    private void upgradeHttpVersion(final HttpVersion version, final SelectionKey key) {
        if (version == HttpVersion.HTTP_2) {
            Http1ConnectionContext currentConnectionContext = (Http1ConnectionContext) key.attachment();
            Http2ConnectionImpl http2ChannelSession = contextReuseManager.checkoutHttp2ConnectionContext();
            http2ChannelSession.reset((SocketChannel) key.channel(), this::handleOnClose);
            key.attach(http2ChannelSession);
            http2ChannelSession.upgrade(currentConnectionContext);
            // continue handling with HTTP2
            http2ChannelSession.handleIncomingData(httpVersion -> upgradeHttpVersion(httpVersion, key));
        } else {
            throw new RuntimeException("Unsupported HTTP version update");
        }
    }

    // TODO remove - temporary for print statement
    private final AtomicLong count = new AtomicLong(0);

    /**
     * Accepts a new socket connection, registering to listen to read and write events on it.
     */
    private void accept() {
        try {
            System.out.println("New connection "+count.incrementAndGet()+" accepted");
            // Go ahead and accept the socket channel and configure it
            // TODO Make sure we have a configured upper limit on number of active connections
            // and keep track of that information
            final var socketChannel = serverSocketChannel.accept();
            if (socketChannel != null) {
                socketChannel.socket().setTcpNoDelay(config.noDelay());
                // TODO socketChannel.socket().setSoTimeout(config.); // not sure it effects non-blocking calls
                socketChannel.configureBlocking(false);
                socketChannel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
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