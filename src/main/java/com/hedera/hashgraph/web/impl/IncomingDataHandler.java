package com.hedera.hashgraph.web.impl;

import com.hedera.hashgraph.web.HttpVersion;
import com.hedera.hashgraph.web.WebServerConfig;
import com.hedera.hashgraph.web.impl.http.Http1ConnectionContext;
import com.hedera.hashgraph.web.impl.http2.Http2ConnectionContext;
import com.hedera.hashgraph.web.impl.session.ConnectionContext;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * Coordinates the work of the {@link ChannelManager} (which handles all networking connections with the clients),
 * the logic for processing those incoming request bytes to turn them into
 * {@link com.hedera.hashgraph.web.WebRequest}s, and the {@link Dispatcher} for dispatching
 * {@link com.hedera.hashgraph.web.WebRequest}s.
 * <p>
 * When closed, the associated {@link ChannelManager} and other classes will also be closed, and any in-flight
 * requests will be asynchronously canceled (some may still complete, but many will be canceled).
 */
public final class IncomingDataHandler implements Runnable, AutoCloseable {
    /**
     * The time we want to allow the {@link ChannelManager} to block waiting for connections
     * in {@link ChannelManager#checkConnections(Duration, AtomicInteger, Predicate)}, before giving up if all
     * connections are idle. This is low enough so when a server is stopped, it stops relatively
     * quickly, but long enough to keep us from a horrible busy loop consuming the CPU needlessly.
     */
    private static final Duration DEFAULT_CHANNEL_TIMEOUT = Duration.ofMillis(500);

    /**
     * Set by the {@link #close()} method when it is time to stop the dispatcher.
     * This is read by one thread, and set by another.
     */
    private volatile boolean shutdown;

    /**
     * The channel manager to use for getting notified whenever a channel is ready to read data.
     * This will never be null.
     */
    private final ChannelManager channelManager;

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
     * The dispatcher to use for dispatching {@link com.hedera.hashgraph.web.WebRequest}s.
     */
    private final Dispatcher dispatcher;

    /**
     * This is a utility class used for reusing the different {@link ConnectionContext} types and subtypes.
     */
    private final ContextReuseManager contextReuseManager;

    /**
     * Create a new instance. This instance can <b>NOT</b> be safely called from other threads,
     * except for the {@link #close()} method, which can be called from any thread.
     *
     * @param config The configuration for the web server. Cannot be null.
     * @param dispatcher The dispatcher to use for dispatching requests. Cannot be null.
     * @param channelManager The channel manager from which to get data from connections. Cannot be null.
     */
    public IncomingDataHandler(
            final WebServerConfig config,
            final Dispatcher dispatcher,
            final ChannelManager channelManager) {
        this.config = Objects.requireNonNull(config);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.channelManager = Objects.requireNonNull(channelManager);
        this.availableConnections = new AtomicInteger(config.maxIdleConnections());
        this.contextReuseManager = new ContextReuseManager(dispatcher);
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
                // Ask the channel manager to process all connections -- add new ones, or call
                // us back for each existing connection with data to be read.
                channelManager.checkConnections(DEFAULT_CHANNEL_TIMEOUT, availableConnections, key -> {
                    if (!shutdown) {
                        final var channel = (SocketChannel) key.channel();
                        // The channel associated with this key has data to be read. If there is
                        // an attachment, then it means we've seen this key before, so we can just
                        // reuse it. If we haven't seen it before, then we'll get a ChannelData and get
                        // it setup.
                        var connectionContext = (ConnectionContext) key.attachment();
                        if (connectionContext == null) {
                            // Always start with HTTP/1.1
                            connectionContext = contextReuseManager.checkoutHttp1ConnectionContext();
                            connectionContext.resetWithNewChannel(channel, availableConnections::incrementAndGet);
                            key.attach(connectionContext);
                            availableConnections.decrementAndGet();
                        }
                        
                        boolean allDataRead = false;
                        try {
                            // Put the data into the input stream
                            allDataRead = !connectionContext.getIn().addData(connectionContext.getChannel());
                        } catch (IOException e) {
                            // The dang channel is closed, we need to clean things up
                            e.printStackTrace();
                            connectionContext.close();
                        }

                        // Delegate to the protocol handler!
                        connectionContext.handle(httpVersion -> upgradeHttpVersion(httpVersion, key));
                        return allDataRead;
                    }
                    return true;
                });
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
     * Handle HTTP version upgrades, only supports HTTP 1.1 to HTTP 2.0 for now
     *
     * @param version The version we are upgrading to
     * @param key The selection key for channel we are upgrading
     */
    private void upgradeHttpVersion(final HttpVersion version, final SelectionKey key) {
        if (version == HttpVersion.HTTP_2) {
            Http1ConnectionContext currentConnectionContext = (Http1ConnectionContext) key.attachment();
            Http2ConnectionContext http2ChannelSession = contextReuseManager.checkoutHttp2ConnectionContext();
            http2ChannelSession.resetWithNewChannel((SocketChannel) key.channel(), availableConnections::incrementAndGet);
            key.attach(http2ChannelSession);
            http2ChannelSession.upgrade(currentConnectionContext);
            // continue handling with HTTP2
            http2ChannelSession.handle(httpVersion -> upgradeHttpVersion(httpVersion, key));
        } else {
            throw new RuntimeException("Unsupported HTTP version update");
        }
    }

    /**
     * Shuts down the dispatcher.
     */
    public void close() {
        shutdown = true;
        channelManager.close();
    }
}