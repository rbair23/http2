package com.hedera.hashgraph.web;

import com.hedera.hashgraph.web.impl.ChannelManager;
import com.hedera.hashgraph.web.impl.Dispatcher;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.ServerSocketChannel;
import java.time.Duration;
import java.util.Objects;

/**
 * A simple web server supporting both HTTP/1.1 and HTTP/2 requests.
 * <p>
 * The implementation of HTTP/2 is based on <a href="https://httpwg.org/specs/rfc9113">RFC9113</a> and does not
 * implement the deprecated priority signaling scheme defined in
 * <a href="https://httpwg.org/specs/rfc9113.html#RFC7540">RFC7540</a>.
 * <p>
 * This implementation of HTTP/2 does not support PUSH_PROMISE or server side push in general at this time.
 */
public final class WebServer {
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
    private volatile Lifecycle lifecycle;

    //-------------- All fields below are related to a running server only

    /**
     * A factory for a {@link ServerSocket} used for handling client connections. The instance is
     * created when the server is started.
     */
    private ServerSocketChannel ssc;

    /**
     *  A factory for creating new connections with clients. Created when the server is started.
     */
    private ServerSocket serverSocket;

    /**
     * The dispatcher is responsible for processing all connections and their data and
     * eventually dispatching web requests to the appropriate {@link WebRequestHandler}.
     * The instance is created during server {@link #start()} and cleared when the server
     * is stopped.
     */
    private Dispatcher dispatcher;

    /**
     * Responsible for managing connections.
     */
    private ChannelManager channelManager;

    /**
     * This thread is used for running the dispatcher. It is created on {@link #start()}
     * and cleared when the server stops.
     */
    private Thread dispatchThread;

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

        // Create and configure the server socket channel to be non-blocking (NIO)
        this.ssc = ServerSocketChannel.open();
        ssc.configureBlocking(false);

        // Bind the socket
        this.serverSocket = ssc.socket();
        serverSocket.bind(config.addr(), config.backlog());

        // Create and start the dang thread
        this.channelManager = new ChannelManager(ssc, config.noDelay());
        this.dispatcher = new Dispatcher(config, routes, config.executor(), channelManager);
        this.dispatchThread = new Thread(dispatcher, "WEB-Dispatcher");
        lifecycle = Lifecycle.STARTED;
        this.dispatchThread.start();
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
        channelManager.shutdown();
        dispatcher.shutdown();
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

        if (dispatchThread != null && dispatchThread != Thread.currentThread()) {
            try {
                dispatchThread.join();
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

    // Only has a valid value if bound (i.e. if the server has been started).

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
