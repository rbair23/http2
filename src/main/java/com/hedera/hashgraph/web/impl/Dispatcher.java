package com.hedera.hashgraph.web.impl;

import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.WebRoutes;
import com.hedera.hashgraph.web.WebServerConfig;
import com.hedera.hashgraph.web.impl.http.HttpProtocolHandler;
import com.hedera.hashgraph.web.impl.http2.Http2ProtocolHandler;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The {@code Dispatcher} coordinates the work of the {@link ChannelManager} (which handles all networking
 * connections with the clients), the {@link ProtocolHandler}s, and the data buffers used by the protocol
 * handlers. It also manages the {@link ExecutorService} (or thread pool) to which
 * {@link com.hedera.hashgraph.web.WebRequest}s are sent. There is a single instance of this class per
 * running {@link com.hedera.hashgraph.web.WebServer}, and it executes on a single thread.
 */
public final class Dispatcher implements Runnable {
    /**
     * The time we want to allow the {@link ChannelManager} to block waiting for connections
     * in {@link ChannelManager#checkConnections(Duration, AtomicInteger, Consumer)}, before giving up if all
     * connections are idle. This is low enough so when a server is stopped, it stops relatively
     * quickly, but long enough to keep us from a horrible busy loop consuming the CPU needlessly.
     */
    private static final Duration DEFAULT_CHANNEL_TIMEOUT = Duration.ofMillis(500);

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
     * A thread pool for submitting work for dispatching for web handlers. This will never be null.
     */
    private final ExecutorService threadPool;

    /**
     * The single, stateless {@link ProtocolHandler} for the HTTP/1.0 and HTTP/1.1 protocols. This will never be null.
     */
    private final HttpProtocolHandler httpProtocolHandler;

    /**
     * The single, stateless {@link Http2ProtocolHandler} for the HTTP/2.0 protocol. This will never be null.
     */
    private final Http2ProtocolHandler http2ProtocolHandler;

    /**
     * The number of additional connections that can be made. There is a configurable upper limit.
     */
    private final AtomicInteger availableConnections;

    /**
     * Each channel has some associated data that we need to maintain. When in use, the channel data is
     * stored on the {@link java.nio.channels.SelectionKey} of the channel. When no longer in use, we want
     * to keep reference to it, so we can reuse it later. So we have a linked-list of unused {@link ChannelData}
     * from which we can take one (from the main thread) and return one (from any of the thread in the
     * {@link #threadPool}).
     */
    private ChannelData headOfUnusedChannelData = null;

    /**
     * Each {@link ChannelData} refers to {@link RequestData}. The number of such objects is dynamic, but we
     * don't want to generate and allocate a lot of garbage, So we have a linked-list of unused {@link RequestData}
     * from which we can take one (from the main thread) and return one (from any of the thread in the
     * {@link #threadPool}).
     */
    private RequestData headOfUnusedRequestData = null;

    /**
     * Create a new instance. This instance can <b>NOT</b> be safely called from other threads,
     * except for the {@link #shutdown()} method.
     *
     * @param config The configuration for the web server. Cannot be null.
     * @param routes The routes for handling a request. Cannot be null.
     * @param threadPool The pool of threads to use for handling a request. Cannot be null.
     * @param channelManager The channel manager from which to get data from connections. Cannot be null.
     */
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
        this.availableConnections = new AtomicInteger(config.maxIdleConnections());
    }

    /**
     * Runs until {@link #shutdown()} is called, or the underlying socket connection fails.
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
                        // The channel associated with this key has data to be read. If there is
                        // an attachment, then it means we've seen this key before, so we can just
                        // reuse it. If we haven't seen it before, then we'll get a ChannelData and get
                        // it setup.
                        var data = (ChannelData) key.attachment();
                        if (data == null) {
                            data = checkoutChannelData();
                            data.channel = (SocketChannel) key.channel();
                            key.attach(data);
                        }

                        // Delegate to the protocol handler!
                        data.protocolHandler.handle2(data, this::dispatch);
                    }
                });
            }
        } catch (IOException fatal) {
            // A fatal IOException has happened. This will stop the web server. We need to publish this
            // quite loudly. I don't yet have any logging strategy in this code (OOOPS), so for now I
            // am just going to have to do system.err.
            System.err.println("WEBSERVER - FATAL. Underlying socket has probably been closed on us");
            fatal.printStackTrace();
        }
    }

    /**
     * Shuts down the dispatcher.
     */
    public void shutdown() {
        shutdown = true;
        synchronized (this) {
            headOfUnusedChannelData = null;
            headOfUnusedRequestData = null;
        }
    }

    /**
     * Called by the {@link #run()} method when there is a request ready to be handled.
     *
     * @param channelData
     * @param reqData
     */
    private void dispatch(ChannelData channelData, RequestData reqData) {
        if (!shutdown) {
            // This callback is invoked when it is time to submit the request.
            // Can I get method and path from the headers?
            final var method = reqData.method;
            final var path = reqData.path;
            final var handler = routes.match(method, path);
            if (handler != null) {
                threadPool.submit(() -> {
                    try(var request = new WebRequestImpl(method, path, reqData.version, reqData.headers)) {
                        handler.handle(request);
                    } catch (RuntimeException ex) {
                        // Oh dang, some exception happened while handling the request. Oof. Well, somebody
                        // needs to send a 500 error.
                        channelData.protocolHandler.handleError(channelData, reqData, ex);
                    } finally {
                        // We finished this thing, one way or another
                        channelData.protocolHandler.endOfRequest(channelData, reqData);
                    }
                });
            } else {
                // Dude, 404
                channelData.protocolHandler.handleNoHandlerError(channelData, reqData);
            }
        }
    }

    private synchronized ChannelData checkoutChannelData() {
        ChannelData data;
        if (headOfUnusedChannelData == null) {
            data = new ChannelData();
        } else {
            data = headOfUnusedChannelData;
            headOfUnusedChannelData = data.next;
            data.next = null;
        }

        data.reset();
        return data;
    }

    private void returnChannelData(ChannelData data) {
        Objects.requireNonNull(data);
        synchronized (this) {
            data.next = headOfUnusedChannelData;
            headOfUnusedChannelData = data;
        }
    }

    private synchronized RequestData checkoutRequestData() {
        if (headOfUnusedRequestData == null) {
            return new RequestData(config.maxRequestSize());
        } else {
            final var data = headOfUnusedRequestData;
            headOfUnusedRequestData = data.next;
            data.next = null;
            return data;
        }
    }

    private void returnRequestData(RequestData data) {
        Objects.requireNonNull(data);
        synchronized (this) {
            data.next = headOfUnusedRequestData;
            headOfUnusedRequestData = data;
        }
    }

    public final class ChannelData implements AutoCloseable {
        private ChannelData next;

        private final HttpInputStream in;
        private final HttpOutputStream out;
        private ProtocolHandler protocolHandler;
        private SocketChannel channel;

        private RequestData singleStreamData;
        private Map<Integer, RequestData> multiStreamData = new HashMap<>();

        private boolean closed = true;

        public ChannelData() {
            this.protocolHandler = httpProtocolHandler;
            this.in = new HttpInputStream(1024);
            this.out = new HttpOutputStream();
            this.closed = false;
        }

        void reset() {
            closed = false;
            this.channel = null;
            this.protocolHandler = httpProtocolHandler;
        }

        @Override
        public void close() throws Exception {
            if (!closed) {
                this.closed = true;
                this.channel = null;
                this.protocolHandler = httpProtocolHandler;
                availableConnections.incrementAndGet();
                // TODO go over all stream datas and close them all
                //      Two chains are needed -- one for ChannelData, and one for RequestData
                //      iterate over everything in the map and close them all, returning them to the pool
            }
        }

        public HttpInputStream getIn() {
            return in;
        }

        public HttpOutputStream getOut() {
            return out;
        }

        public ProtocolHandler getProtocolHandler() {
            return protocolHandler;
        }

        public void setProtocolHandler(ProtocolHandler protocolHandler) {
            this.protocolHandler = protocolHandler;
        }
    }

    public final class RequestData implements AutoCloseable {

        private enum State {
            COLLECTING_HEADERS,
            COLLECTING_BODY,
            START
        }

        // This is null, unless the Data has been reset and is moved into the
        // "unused" queue. Then it will point to the next data in the queue.
        // The queue is a singly-linked list, where instances put back into the queue
        // are added to the head, and items removed from the queue are removed from the head.
        // That way we don't need pointers going forward and backward through the queue.
        private RequestData next;

        private byte[] data;
        private int dataLength;
        private WebHeaders headers; // This is garbage on each request, or maybe not?
        private String method;
        private String path;
        private String version;
        private State state = State.START;


        RequestData(int bufferSize) {
            this.data = new byte[bufferSize];
        }

        @Override
        public void close() {
            headers = null;
            dataLength = 0;
            state = State.START;
            returnRequestData(this);
        }

        public State getState() {
            return state;
        }

        public void setState(State state) {
            this.state = state;
        }

        public byte[] getData() {
            return data;
        }

        public int getDataLength() {
            return dataLength;
        }

        public WebHeaders getHeaders() {
            return headers;
        }

        public void setDataLength(int dataLength) {
            this.dataLength = dataLength;
        }

        public void setHeaders(WebHeaders headers) {
            this.headers = headers;
        }
    }
}
