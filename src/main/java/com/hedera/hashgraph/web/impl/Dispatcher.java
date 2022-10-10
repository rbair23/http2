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
    private ChannelData idleChannelData = null;

    /**
     * Each {@link ChannelData} refers to {@link RequestData}. The number of such objects is dynamic, but we
     * don't want to generate and allocate a lot of garbage, So we have a linked-list of unused {@link RequestData}
     * from which we can take one (from the main thread) and return one (from any of the thread in the
     * {@link #threadPool}).
     */
    private RequestData idleRequestData = null;

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
                            final var channel = (SocketChannel) key.channel();
                            data = checkoutChannelData(channel);
                            key.attach(data);
                        }

                        try {
                            // Put the data into the input stream
                            data.in.addData(data.channel);

                            // Delegate to the protocol handler!
                            data.protocolHandler.handle(data, this::dispatch);
                        } catch (IOException e) {
                            // The dang channel is closed, we need to clean things up
                            data.close();
                        }
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
            idleChannelData = null;
            idleRequestData = null;
        }
    }

    /**
     * Called by the {@link #run()} method when there is a request ready to be handled.
     *
     * @param channelData The {@link ChannelData} instance associated with the request to dispatch. Not null or closed.
     * @param reqData The {@link RequestData} instance with the request data to dispatch. Not null or closed.
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

    /**
     * Gets a {@link ChannelData} instance that can be used for a new channel. If there are
     * no remaining instance in the {@link #idleChannelData}, then a new instance
     * is created. The {@link #availableConnections} is used to make sure we never create
     * too many of these.
     *
     * @param channel The channel to provide to this instance. Cannot be null.
     * @return A usable and freshly configured {@link ChannelData} instance. Never null.
     */
    private synchronized ChannelData checkoutChannelData(SocketChannel channel) {
        Objects.requireNonNull(channel);

        ChannelData data;
        if (idleChannelData == null) {
            data = new ChannelData();
        } else {
            data = idleChannelData;
            idleChannelData = data.next;
            data.next = null;
        }

        data.reset(channel);
        return data;
    }

    /**
     * Represents the data needed on a per-channel basis for parsing requests. These objects are meant to be
     * reused across channels, to cut down on garbage collector pressure. Creation of these objects is controlled
     * by {@link #checkoutChannelData(SocketChannel)}. When the object is closed, it will shut down the channel
     * and then clean itself up a bit and put itself back into the {@link #idleChannelData} list.
     * <p>
     * Each channel has associated with it two streams -- an input stream from which bytes on the channel are
     * made available, and an output stream into which bytes are to be sent to the client though the channel.
     * Each channel also has a single associated {@link ProtocolHandler}. All channels start of with the
     * {@link HttpProtocolHandler}, but may be upgraded to use an {@link Http2ProtocolHandler} if the original
     * protocol detects it. When the channel data object is closed, it goes back to using the HTTP protocol handler.
     * <p>
     * There is some ugliness here because we have two ways of using {@link RequestData} here. For HTTP 1.0 and
     * 1.1, we can reuse a single {@link RequestData} instance. For HTTP 2.0, we need to use a map of them,
     * because there is one per stream, and they are key'd by stream ID (an integer). Since this single instance
     * of {@link ChannelData} is intended to be used for both, we have to provide both the "single stream data"
     * request data instance and a (potentially empty) map of them.
     * <p>
     * When the {@link ChannelData} is closed, all {@link RequestData} instances are closed and returned to the list of
     * idle instances.
     */
    public final class ChannelData implements AutoCloseable {
        /**
         * Null when this instance is in use or when it is the last item in the list, otherwise points to the
         * next idle instance in the idle chain.
         */
        private ChannelData next;

        /**
         * A single instance that lives with this instance. When this {@link ChannelData} is closed, the
         * input stream is reset in anticipation of the next channel to be serviced.
         */
        private final HttpInputStream in;

        /**
         * A single instance that lives with this instance. When this {@link ChannelData} is closed, the
         * output stream is reset in anticipation of the next channel to be serviced.
         */
        private final HttpOutputStream out;

        /**
         * The protocol handler associated with this {@link ChannelData} instance. This always starts
         * off pointing to a {@link HttpProtocolHandler}, but can be upgraded to {@link Http2ProtocolHandler}.
         * Whichever instance is here, is used for handling all requests on this channel. For example,
         * upgrading to HTTP/2.0 is a one-way operation, the channel will only deal with HTTP/2.0 going forward.
         */
        private ProtocolHandler protocolHandler;

        /**
         * The underlying {@link SocketChannel} that we use for reading and writing data.
         */
        private SocketChannel channel;

        /**
         * This {@link RequestData} instance is used by the HTTP 1/1.1 implementation, where there is
         * a single stream of requests per channel. Closed on {@link #close()}.
         */
        private RequestData singleStreamData;

        /**
         * This map of {@link RequestData} is used by the HTTP/2.0 implementation, where there are
         * multiple requests per channel. Cleared on {@link #close()}.
         */
        private Map<Integer, RequestData> multiStreamData = new HashMap<>();

        /**
         * Keeps track of whether the instance is closed. Set to true in {@link #close()} and set to
         * false in {@link #reset(SocketChannel)}.
         */
        private boolean closed = true;

        /**
         * Create a new instance.
         */
        public ChannelData() {
            this.protocolHandler = httpProtocolHandler;
            this.in = new HttpInputStream(config.maxRequestSize());
            this.out = new HttpOutputStream();
            this.closed = false;
        }

        /**
         * Resets the state of this channel before being reused.
         *
         * @param channel The new channel to hold data for.
         */
        void reset(SocketChannel channel) {
            closed = false;
            this.channel = Objects.requireNonNull(channel);
            this.protocolHandler = httpProtocolHandler;
        }

        @Override
        public void close() {
            if (!closed) {
                this.closed = true;

                try {
                    this.channel.close();
                } catch (IOException ignored) {
                    // TODO Maybe a warning? Or an info? Or maybe just debug logging.
                }

                this.channel = null;
                this.in.reset();
                this.out.reset();
                this.protocolHandler = httpProtocolHandler;
                if (this.singleStreamData != null) {
                    this.singleStreamData.close();
                }

                // TODO Probably produces a bunch of garbage?
                this.multiStreamData.forEach((k, v) -> v.close());
                this.multiStreamData.clear();

                synchronized (Dispatcher.this) {
                    this.next = idleChannelData;
                    idleChannelData = this;
                }

                availableConnections.incrementAndGet();
            }
        }

        /**
         * Gets the input stream from which data from the channel can be read.
         *
         * @return The stream. Never null.
         */
        public HttpInputStream getIn() {
            return in;
        }

        /**
         * Gets the output stream to which data may be written into the channel.
         *
         * @return The stream. Never null.
         */
        public HttpOutputStream getOut() {
            return out;
        }

        /**
         * The current protocol handler.
         *
         * @return
         */
        public ProtocolHandler getProtocolHandler() {
            // TODO Is this method really needed? Or can we just remove it?
            return protocolHandler;
        }

        // TODO Maybe rename to "upgrade"?
        public void setProtocolHandler(ProtocolHandler protocolHandler) {
            this.protocolHandler = Objects.requireNonNull(protocolHandler);
        }

        /**
         * Gets the {@link RequestData} for use in single-stream scenarios.
         * @return a non-null instance
         */
        public RequestData getRequestData() {
            if (singleStreamData == null) {
                singleStreamData = checkoutRequestData();
            }
            return singleStreamData;
        }

        /**
         * Gets the {@link RequestData} for use in multi-stream scenarios.
         * @return a non-null instance
         */
        public RequestData getRequestData(int streamId) {
            return multiStreamData.computeIfAbsent(streamId, k -> checkoutRequestData());
        }

        /**
         * Gets a {@link RequestData} instance that can be used for a new request. If there are
         * no remaining instance in the {@link #idleRequestData}, then a new instance
         * is created. TODO How to make sure we don't get too many requests??
         *
         * @return A usable and freshly configured {@link ChannelData} instance. Never null.
         */
        private RequestData checkoutRequestData() {
            synchronized (Dispatcher.this) {
                if (idleRequestData == null) {
                    return new RequestData(config.maxRequestSize());
                } else {
                    final var data = idleRequestData;
                    idleRequestData = data.next;
                    data.next = null;
                    return data;
                }
            }
        }

        public RequestData getSingleStreamData() {
            if (singleStreamData == null) {
                singleStreamData = checkoutRequestData();
            }
            return singleStreamData;
        }
    }

    /**
     * Contains all state need to parse and create a valid {@link com.hedera.hashgraph.web.WebRequest}.
     * The values within this class are set based on logic in the {@link ProtocolHandler} on the
     * connection associated with this request.
     */
    public final class RequestData implements AutoCloseable {

        /**
         * Enum for parse states of a HTTP Request
         *
         *  - BEGIN - before the start of file
         *  - METHOD URI VERSION HTTP2_PREFACE? - Start file
         *  - HEADER_KEY HEADER_VALUE - repeated
         *  - COLLECTING_BODY
         */
        public enum State {
            BEGIN,
            METHOD,
            URI,
            VERSION,
            HTTP2_PREFACE,
            HEADER_KEY,
            HEADER_VALUE,
            COLLECTING_BODY,
        }

        // This is null, unless the Data has been reset and is moved into the
        // "unused" queue. Then it will point to the next data in the queue.
        // The queue is a singly-linked list, where instances put back into the queue
        // are added to the head, and items removed from the queue are removed from the head.
        // That way we don't need pointers going forward and backward through the queue.
        private RequestData next;

        /**
         * The data buffer. This is sized to be large enough to contain all headers, or all body, or an entire
         * HTTP/2.0 frame (whichever is larger). Initially header info is written into it, and then we write
         * over it with body info, and then eventually create an InputStream over the data for use in the
         * {@link com.hedera.hashgraph.web.WebRequest}.
         */
        private final byte[] data;

        /**
         * The index into {@link #data} where valid data ends.
         */
        private int dataLength;

        /**
         * A new instance is created for each request. After we read all the header data, we parse it
         * and produce an instance of {@link WebHeaders}.
         */
        private WebHeaders headers; // This is garbage on each request, or maybe not?

        /**
         * Parsed from the input stream by the {@link ProtocolHandler} and set here.
         */
        private String method;

        /**
         * Parsed from the input stream by the {@link ProtocolHandler} and set here.
         */
        private String path;

        /**
         * Parsed from the input stream by the {@link ProtocolHandler} and set here.
         */
        private String version;

        /**
         * Current state of the parsing process.
         */
        private State state = State.BEGIN;

        /**
         * Create a new instance
         *
         * @param bufferSize the size of the buffer (make it large enough but not too large!)
         */
        RequestData(int bufferSize) {
            this.data = new byte[bufferSize];
        }

        @Override
        public void close() {
            headers = null;
            dataLength = 0;
            state = State.BEGIN;
            method = null;
            path = null;
            version = null;
            returnRequestData(this);
        }

        /**
         * Gets the current state.
         *
         * @return the state. Not null.
         */
        public State getState() {
            return state;
        }

        /**
         * Sets the current state.
         *
         * @param state the state to set, cannot be null.
         */
        public void setState(State state) {
            this.state = Objects.requireNonNull(state);
        }

        /**
         * Gets the <b>RAW</b> data buffer. Be careful!
         *
         * @return The data buffer, no copies.
         */
        public byte[] getData() {
            return data;
        }

        /**
         * Gets the length of the data.
         *
         * @return The data length, will be non-negative
         */
        public int getDataLength() {
            return dataLength;
        }

        /**
         * Update the end index of valid data.
         *
         * @param dataLength the new length
         */
        public void setDataLength(int dataLength) {
            assert dataLength >= 0;
            this.dataLength = dataLength;
        }

        /**
         * The web headers. This can be null, if the headers have not yet been created.
         * This is created fresh for every request.
         *
         * @return the web headers.
         */
        public WebHeaders getHeaders() {
            return headers;
        }

        /**
         * Sets the computed web headers.
         *
         * @param headers shouldn't be null
         */
        public void setHeaders(WebHeaders headers) {
            this.headers = Objects.requireNonNull(headers);
        }

        /**
         * Returns the given {@link RequestData} to the idle set.
         * @param data The data, not null.
         */
        private void returnRequestData(RequestData data) {
            Objects.requireNonNull(data);
            synchronized (Dispatcher.this) {
                data.next = idleRequestData;
                idleRequestData = data;
            }
        }

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
            System.out.println("method = " + method);
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
            System.out.println("path = " + path);
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
            System.out.println("version = " + version);
        }
    }
}
