package com.hedera.hashgraph.web.impl;

import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.WebRequest;
import com.hedera.hashgraph.web.WebRoutes;
import com.hedera.hashgraph.web.impl.http.HttpProtocolHandler;
import com.hedera.hashgraph.web.impl.http2.Http2ProtocolHandler;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * The {@code Dispatcher} coordinates the work of the {@link ChannelManager} (which handles all networking
 * connections with the clients), the {@link ProtocolHandler}s, and the data buffers used by the protocol
 * handlers. It also manages the {@link Executor} (or thread pool) to which {@link com.hedera.hashgraph.web.WebRequest}s
 * are sent. There is a single instance of this class per running {@link com.hedera.hashgraph.web.WebServer},
 * and it executes on a single thread.
 */
public class Dispatcher implements Runnable {
    private volatile boolean quit;

    private final ChannelManager channelManager;
    private final Executor connectionPool;

    private final HttpProtocolHandler httpProtocolHandler;
    private final Http2ProtocolHandler http2ProtocolHandler;

    // If we need a new request object, and we don't already have one,
    // we will create one. But then we will reuse it later.
    private final WebRequestImpl headOfUnusedRequests = null;

    public Dispatcher(
            final ChannelManager channelManager,
            final WebRoutes routes,
            final Executor connectionPool) {
        this.channelManager = Objects.requireNonNull(channelManager);
        this.connectionPool = Objects.requireNonNull(connectionPool);
        this.httpProtocolHandler = new HttpProtocolHandler(routes);
        this.http2ProtocolHandler = new Http2ProtocolHandler(routes);
    }

    @Override
    public void run() {
        while (!quit) {
            channelManager.run();

        }
    }

    public void shutdown() {
        // Causes the run() loop to terminate, possibly "timeout" milliseconds later.
        quit = true;
    }

    /**
     * In HTTP/1.0 or HTTP/1.1, there is one "stream" per connection. In HTTP/2.0, there may be
     * multiple "streams" per connection. This data is per stream, rather than per connection.
     */
    private static final class WebRequestImpl implements WebRequest {
        // This is null, unless the WebRequestImpl has been closed and is moved into the
        // "unused" queue. Then it will point to the next web request in the queue.
        // The queue is a singly-linked list, where instances put back into the queue
        // are added to the head, and items removed from the queue are removed from the head.
        // That way we don't need pointers going forward and backward through the queue.
        // NOTE: We do have to worry about threading here, because multiple requests may
        // be closed from different threads concurrently, but only a single thread is
        // fetching an unused request from the queue.
        private WebRequestImpl next;

        private WebHeaders requestHeaders;
        private byte[] headerData = new byte[1024];
        private byte[] bodyData = new byte[1024 * 6];
        private int bodyLength = 0;

        @Override
        public void close() throws Exception {

        }

        @Override
        public WebHeaders getRequestHeaders() {
            return requestHeaders;
        }
    }
}
