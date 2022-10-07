package com.hedera.hashgraph.web.impl;

import com.hedera.hashgraph.web.WebRoutes;
import com.hedera.hashgraph.web.WebServerConfig;
import com.hedera.hashgraph.web.impl.http.HttpProtocolHandler;
import com.hedera.hashgraph.web.impl.http2.Http2ProtocolHandler;

import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Attack Vectors:
 *   - Open a bunch of connections but don't send data, or just send pings
 *      - Make sure TCP_TIMEOUT is set to a reasonable value
 *      - Have a reasonable configuration for max amount of time a connection can be open
 *          - Also covers the case where data is dribbling in slowly
 *      - Find the right value for the size of backlog of connections
 *      - Close an idle connection that isn't sending data
 *   - A connection sends too many header values (too much header data)
 *      - Detect too much header data and close with error
 *   - A connection wants to send more data than it should
 *      - Detect and close with error
 *   - A connection is valid but sends garbage header data
 *      - We won't detect the garbage header data until we go to parse it, which happens later.
 *        So we're just open to this kind of attack.
 *   - Somebody sends completely random garbage data for the header. Can they exploit us?
 *      - The parser has to be careful about things like max-key-size, max-value-size, and
 *        throw exceptions on any parse errors.
 *      - No code execution (sql injection, etc.) is possible at this phase
 *      - Mitigate with fuzz testing
 *
 * The {@code Dispatcher} coordinates the work of the {@link ChannelManager} (which handles all networking
 * connections with the clients), the {@link ProtocolHandler}s, and the data buffers used by the protocol
 * handlers. It also manages the {@link ExecutorService} (or thread pool) to which
 * {@link com.hedera.hashgraph.web.WebRequest}s are sent. There is a single instance of this class per
 * running {@link com.hedera.hashgraph.web.WebServer}, and it executes on a single thread.
 */
public class Dispatcher implements Runnable {
    /**
     * Set by the {@link #shutdown()} method when it is time to stop the dispatcher.
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
     * The web server routes. This will never be null.
     */
    private final WebRoutes routes;

    /**
     * A thread pool for submitting work for dispatching for web handlers.
     */
    private final ExecutorService threadPool;

    private final HttpProtocolHandler httpProtocolHandler;
    private final Http2ProtocolHandler http2ProtocolHandler;

    // NOTE: We do have to worry about threading here, because multiple requests may
    // be closed from different threads concurrently, but only a single thread is
    // fetching an unused request from the queue.
    private RequestData headOfUnusedData = null;

    public Dispatcher(
            final WebServerConfig config,
            final WebRoutes routes,
            final ExecutorService threadPool,
            final ChannelManager channelManager) {
        this.config = Objects.requireNonNull(config);
        this.routes = Objects.requireNonNull(routes);
        this.threadPool = Objects.requireNonNull(threadPool);
        this.channelManager = Objects.requireNonNull(channelManager);
        this.http2ProtocolHandler = new Http2ProtocolHandler(routes);
        this.httpProtocolHandler = new HttpProtocolHandler(routes, http2ProtocolHandler);
    }

    @Override
    public void run() {
        final var checkConnectionBlockingDuration = Duration.ofMillis(500);
        while (!shutdown) {
            // Ask the channel manager to process all connections -- add new ones, or call
            // us back for each existing connection with data to be read.
            channelManager.checkConnections(checkConnectionBlockingDuration, key -> {
                // The channel associated with this key has data to be read. If there is
                // an attachment, then it means we've seen this key before, so we can just
                // reuse it. If we haven't seen it before, then we'll get a Data and get
                // it setup.
                var data = (RequestData) key.attachment();
                if (data == null) {
                    data = checkoutData();
                    data.channel = (SocketChannel) key.channel();
                    key.attach(data);
                }

                // Delegate to the protocol handler!
                data.protocolHandler.handle2(data, this::dispatch);
            });
        }
    }

    public void shutdown() {
        // Causes the checkConnections() loop to terminate, possibly "timeout" milliseconds later.
        shutdown = true;
        channelManager.shutdown();
        threadPool.shutdown();
        synchronized (this) {
            headOfUnusedData = null;
        }
    }

    private void dispatch(RequestData reqData) {
        // This callback is invoked when it is time to submit the request.
        try (reqData) {
            final var method = reqData.req.getRequestMethod();
            final var path = reqData.req.getPath();
            final var handler = routes.match(method, path);
            if (handler != null) {
                handler.handle(reqData.req);
            }
        }

        returnData(reqData);
    }

    private synchronized RequestData checkoutData() {
        if (headOfUnusedData == null) {
            return new RequestData(httpProtocolHandler);
        } else {
            final var data = headOfUnusedData;
            headOfUnusedData = data.next;
            data.next = null;
            return data;
        }
    }

    private void returnData(RequestData data) {
        Objects.requireNonNull(data);
        synchronized (this) {
            data.next = headOfUnusedData;
            headOfUnusedData = data;
        }
    }

    public final class RequestData implements AutoCloseable {
        private final WebRequestImpl req = new WebRequestImpl();
        private final ProtocolHandler initialHandler;

        // This is null, unless the Data has been reset and is moved into the
        // "unused" queue. Then it will point to the next data in the queue.
        // The queue is a singly-linked list, where instances put back into the queue
        // are added to the head, and items removed from the queue are removed from the head.
        // That way we don't need pointers going forward and backward through the queue.
        private RequestData next;
        private SocketChannel channel;
        private ProtocolHandler protocolHandler;

        RequestData(ProtocolHandler initialHandler) {
            this.initialHandler = Objects.requireNonNull(initialHandler);
            this.protocolHandler = initialHandler;
        }

        @Override
        public void close() {
            this.req.close();
            this.channel = null;
            this.protocolHandler = this.initialHandler;
        }
    }
}
