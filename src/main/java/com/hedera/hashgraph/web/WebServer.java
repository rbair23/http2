package com.hedera.hashgraph.web;

import com.hedera.hashgraph.web.impl.ChannelManager;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.IncomingDataHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A simple web server supporting both HTTP/1.1 and HTTP/2.0 requests. The server architecture is designed to
 * use minimal object allocation, configurable numbers of threads, and to minimize buffer copies. It is not
 * designed to support large request bodies, but does support arbitrarily sized response bodies.
 * <p>
 * The server can be started at most once. It cannot be restarted.
 * <p>
 * The implementation of HTTP/2.0 is based on <a href="https://httpwg.org/specs/rfc9113">RFC9113</a> and does not
 * implement the deprecated priority signaling scheme defined in
 * <a href="https://httpwg.org/specs/rfc9113.html#RFC7540">RFC7540</a>.
 * <p>
 * This implementation of HTTP/2.0 does not support PUSH_PROMISE or server side push in general at this time.
 */
public final class WebServer {

    /**
     * The server lifecycle.
     */
    private enum Lifecycle {
        NOT_STARTED,
        STARTED,
        FINISHING,
        FINISHED
    }

    /**
     * The port number to use if you want the server to choose an ephemeral port at random. Great for testing.
     */
    public static final int EPHEMERAL_PORT = 0;

    /**
     * An incrementing counter, increased by one for each WebServer started. Used for debugging, but otherwise
     * it is unused.
     */
    private static AtomicInteger AUTO_INC_COUNTER = new AtomicInteger(0);

    /**
     * Configuration settings for the web server. Once set, the configuration cannot be changed.
     */
    private final WebServerConfig config;

    /**
     * The routes configured on this server. The routes may be modified at any time.
     */
    private final WebRoutes routes;

    /**
     * Lifecycle state. The server progresses from {@link Lifecycle#NOT_STARTED} through all states
     * until reaching {@link Lifecycle#FINISHED}. It moves linearly through states and never goes
     * back to any previous state. Once used, a web server cannot be reused.
     */
    private volatile Lifecycle lifecycle = Lifecycle.NOT_STARTED;

    //-------------- All fields below are related to a running server only

    /**
     * The server-side socket channel used for communication with the client. The instance is
     * created when the server is started.
     */
    private ServerSocketChannel ssc;

    /**
     * Handles incoming data from the {@link #ssc} by distributing it to various implementation
     * classes that will process that data and eventually create a request to be handled by a route.
     */
    private IncomingDataHandler incomingDataHandler;

    /**
     * This thread is used for running the dispatcher. It is created on {@link #start()}
     * and cleared when the server stops.
     */
    private Thread incomingDataHandlerThread;

    /**
     * Create a new web server with default configuration. By default, the server will use
     * port 8080 on the local host. To use an ephemeral port, create the server with a web
     * server configuration with a port number of 0.
     */
    public WebServer() {
        this("localhost", 8080);
    }

    /**
     * Create a new web server with given host and port. To use an ephemeral port, use the value of 0.
     *
     * @param host The host to bind to. Must not be null.
     * @param port The port to bind to.
     */
    public WebServer(String host, int port) {
        this(new WebServerConfig.Builder()
                .host(host)
                .port(port)
                .build());
    }

    /**
     * Create a new web server with the given configuration.
     */
    public WebServer(WebServerConfig config) {
        this.config = Objects.requireNonNull(config);
        this.routes = new WebRoutes();
    }

    /**
     * Starts the web server. A server cannot be started more than once,
     * or restarted after it is used.
     *
     * @throws IOException If we cannot bind to the configured port
     * @throws IllegalStateException If the server has ever been started before
     */
    public void start() throws IOException {
        if (lifecycle != Lifecycle.NOT_STARTED) {
            throw new IllegalStateException("Server has already been started");
        }

        // Use this version number in debugging contexts.
        final int versionNumber = AUTO_INC_COUNTER.incrementAndGet();

        // Create and configure the server socket channel to be non-blocking (NIO)
        this.ssc = ServerSocketChannel.open();
        ssc.configureBlocking(false);

        // Bind the socket
        final ServerSocket serverSocket = ssc.socket();
        serverSocket.bind(config.addr(), config.backlog());

        // Create and start the dang thread
        final var dispatcher = new Dispatcher(routes, config.executor());
        final var channelManager = new ChannelManager(ssc, config.noDelay());
        this.incomingDataHandler = new IncomingDataHandler(config, dispatcher, channelManager);
        this.incomingDataHandlerThread = new Thread(incomingDataHandler, "WebServer-" + versionNumber);
        lifecycle = Lifecycle.STARTED;
        this.incomingDataHandlerThread.start();
    }

    /**
     * Stops the server, throwing an Interrupted exception if stopping takes more than {@code delay}.
     *
     * @param delay
     */
    public void stop(Duration delay) {
        if (delay.isNegative()) {
            throw new IllegalArgumentException("The delay must be non-negative");
        }

        lifecycle = Lifecycle.FINISHING;

        try {
            ssc.close();
        } catch (IOException ignored) {
        }

//        this.selector.wakeup();
        config.executor().shutdown();
        incomingDataHandler.close();
//        // TODO Replace the use of System.currentTimeMillis with something like from platform that
//        //      lets me fake out the time for testing purposes.
//        long latest = System.currentTimeMillis() + delay * 1000L;
//        while (System.currentTimeMillis() < latest) {
//            // TODO Right now this won't work at all. The dispatcher has to handle some kind of
//            //      in flight "event" to indicate it is time to close, allowing it to close things
//            //      down gracefully. I don't have that yet, so this will hang indefinitely.
//            delay();
//            if (finished) {
//                break;
//            }
//        }
        lifecycle = Lifecycle.FINISHED;
//        selector.wakeup();

//        synchronized (allConnections) {
//            for (HttpConnection c : allConnections) {
//                c.close();
//            }
//        }
//        allConnections.clear();
//        idleConnections.clear();
//        timer.cancel();
//        if (timer1Enabled) {
//            timer1.cancel();
//        }

        if (incomingDataHandlerThread != null && incomingDataHandlerThread != Thread.currentThread()) {
            try {
                incomingDataHandlerThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
//                logger.log (System.Logger.Level.TRACE, "ServerImpl.stop: ", e);
            }
        }
    }

    /**
     * Gets the routes on this server. The same instance is returned every time.
     *
     * @return The routes. This will never be null.
     */
    public WebRoutes getRoutes() {
        return routes;
    }

    /**
     * Gets the web server's configuration. The same instance is returned every time.
     *
     * @return The server config. This will never be null.
     */
    public WebServerConfig getConfig() {
        return config;
    }

    /**
     * Gets the bound {@link InetSocketAddress}. Until the server is started, this method will
     * always return null. After the server has been started, it will return the bound address.
     * After the server is stopped, it will return null.
     *
     * @return The bound address, if the server is bound, else null.
     */
    public InetSocketAddress getBoundAddress() {
        return ssc == null ? null : (InetSocketAddress) ssc.socket().getLocalSocketAddress();
    }
}
