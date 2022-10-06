package com.hedera.hashgraph.web;

import com.hedera.hashgraph.web.impl.AcceptHandler;
import com.hedera.hashgraph.web.impl.http.HttpContextImpl;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * A simple web server supporting both HTTP/1.1 and HTTP/2 requests. The API is based on the Oracle JDK's
 * {@link HttpServer} class.
 * <p>
 * The implementation of HTTP/2 is based on <a href="https://httpwg.org/specs/rfc9113">RFC9113</a> and does not
 * implement the deprecated priority signaling scheme defined in
 * <a href="https://httpwg.org/specs/rfc9113.html#RFC7540">RFC7540</a>.
 * <p>
 * This implementation of HTTP/2 does not support PUSH_PROMISE or server side push in general at this time.
 */
public final class WebServer extends HttpServer {
    /**
     * Defines the default value for the backlog.
     * <p>
     * Multiple clients may attempt to connect to the server at roughly the same time. In
     * NIO, a single thread waits for a socket "accept" event, does a very small amount of
     * work, and then becomes available for the next "accept" event. If clients connect faster
     * than the server can accept them, then a backlog builds up. If the backlog exceeds some
     * configured value, then additional connections are refused.
     * <p>
     * At some point, the number of open connections will exceed a threshold, and it will become
     * necessary to slow down or stop accepting connections. If that happens, the backlog will also
     * be filled, until there is no backlog space available, and new connection attempts are refused.
     * <p>
     * It turns out, if the value is <= 0, then a default backlog value is chosen by Java.
     */
    private static final int DEFAULT_BACKLOG = 0;

    /**
     * A concurrent map of path-to-handler. The {@link HttpContextImpl} is just the information around
     * a handler (specifically, the handler and its path and interceptors or "filters"). So every path can
     * have at most one handler. This class is threadsafe, so the context map must be threadsafe as well.
     */
    private final Map<String, HttpContextImpl> contextMap = new ConcurrentHashMap<>();

    /**
     * The internet address the server is bound to. This can be specified in the constructor, or it
     * can be specified via the {@link #bind(InetSocketAddress, int)} method.
     */
    private InetSocketAddress addr;

    /**
     * The executor used for handling a connection. This executor may be specified by the user. By default,
     * it is single-threaded, meaning that only a single request can be handled at a time (!!).
     */
    private Executor executor = null;

    /**
     * A factory for a {@link ServerSocket} used for handling client connections.
     */
    private final ServerSocketChannel ssc;

    /**
     *  A factory for creating new connections with clients. Only a single instance of this is used, but
     *  it may be created through either the constructor or through the {@link #bind(InetSocketAddress, int)}
     *  method. Once created, it is never changed or destroyed.
     */
    private ServerSocket serverSocket;

    /**
     * NIO Selector associated with the {@link #listenerKey} and {@link #serverSocket}.
     */
    private Selector selector = Selector.open();

    /**
     * This key is used to look up "accept" events from the {@link #serverSocket}, and to close down the
     * socket when we're done.
     */
    private SelectionKey listenerKey;

    /**
     * Lifecycle state to keep track of whether we have bound to a particular hostname and port. This
     * can only happen once. It can happen in the constructor or in the {@link #bind(InetSocketAddress, int)}.
     */
    private boolean bound = false;

    /**
     * Lifecycle state to keep track of whether the server has started listening.
     */
    private boolean started = false;

    /**
     * Lifecycle state to indicate that we are terminating, but not yet stopped.
     */
    private boolean terminating = false;

    /**
     * Lifecycle state to keep track of whether the server has finished.
     */
    private volatile boolean finished = false;

    /**
     * Listens to all NIO events, and handles them.
     */
    private AcceptHandler dispatcher;

    /**
     * This thread responds to all NIO events, such as when new connections become available for
     * "accept", and when a connection becomes available for read.
     */
    private Thread dispatchThread;

    /**
     * Create a new Http2Server unbound to any port.
     *
     * @throws IOException Cannot actually be thrown.
     */
    public WebServer() throws IOException {
        this(null, DEFAULT_BACKLOG);
    }

    /**
     * Creates a new Http2Server on the given address.
     *
     * @param addr The address to bind to. Cannot be null.
     * @throws IOException If the socket cannot be bound.
     */
    public WebServer(InetSocketAddress addr) throws IOException {
        this(addr, DEFAULT_BACKLOG);
    }

    /**
     * Creates a new Http2Server on the given port and with the given backlog
     *
     * @param addr The address to bind to. Cannot be null.
     * @param backlog The backlog for unhandled new connections. If the value is less than or equal to 0,
     *                then Java will pick a default value.
     * @throws IOException If the socket cannot be bound.
     */
    public WebServer(InetSocketAddress addr, int backlog) throws IOException {
        this.addr = addr; // can be null

        // Create and configure the server socket channel to be non-blocking (NIO)
        this.ssc = ServerSocketChannel.open();
        ssc.configureBlocking(false);

        // Pre-bind if an address was supplied
        if (addr != null) {
            this.serverSocket = ssc.socket();
            serverSocket.bind(addr, backlog);
            bound = true;
        }

        // Create the listener key for listening to new connection "accept" events
        this.selector = Selector.open();
        this.listenerKey = ssc.register(selector, SelectionKey.OP_ACCEPT);
    }

    @Override
    public void bind(InetSocketAddress addr, int backlog) throws IOException {
        if (bound) {
            throw new BindException("Http2Server already bound");
        }

        // If the server hasn't already been bound, then we will bind it now.
        this.addr = Objects.requireNonNull(addr);
        this.serverSocket = ssc.socket();
        serverSocket.bind(addr, backlog);
        bound = true;
    }

    @Override
    public void start() {
        if (!bound || started || finished) {
            throw new IllegalStateException("Server is not bound, or has already started, or has already finished");
        }

        // Create a default executor if one has not been supplied by the user. Right now I'm using
        // a single threaded executor to match the behavior of Sun's HttpServer, but I'm not sure
        // that is actually the best behavior.
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor();
        }

        // Create and start the dang thread
        this.dispatcher = new AcceptHandler(ssc, listenerKey, selector, Duration.ofSeconds(1), executor);
        this.dispatchThread = new Thread(dispatcher, "HTTP2-Dispatcher");
        started = true;
        this.dispatchThread.start();
    }

    @Override
    public void setExecutor(Executor executor) {
        if (started) {
            throw new IllegalStateException("The server is already running");
        }
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public Executor getExecutor() {
        return this.executor;
    }

    @Override
    public void stop(int delay) {
        if (delay < 0) {
            throw new IllegalArgumentException("The delay must be non-negative");
        }

        this.terminating = true;

        try {
            ssc.close();
        } catch (IOException ignored) {
        }

        this.selector.wakeup();

        this.dispatcher.shutdown();
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
        finished = true;
        selector.wakeup();

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

    @Override
    public HttpContext createContext(String path, HttpHandler handler) {
        Objects.requireNonNull(path);
        Objects.requireNonNull(handler);
        return contextMap.compute(path, (k, v) -> new HttpContextImpl(this, path, handler));
    }

    @Override
    public HttpContext createContext(String path) {
        Objects.requireNonNull(path);
        return contextMap.compute(path, (k, v) -> new HttpContextImpl(this, path));
    }

    @Override
    public void removeContext(String path) throws IllegalArgumentException {
        Objects.requireNonNull(path);
        contextMap.remove(path);
    }

    @Override
    public void removeContext(HttpContext context) {
        Objects.requireNonNull(context);
        contextMap.remove(context.getPath());
    }

    @Override
    public InetSocketAddress getAddress() {
        return (InetSocketAddress) ssc.socket().getLocalSocketAddress();
    }

    // TODO: Be sure that the loop that is accepting connections blocks if the total number of concurrent
    //       connections is met or exceeded.

    private void delay() {
        Thread.yield();
        try {
            Thread.sleep (200);
        } catch (InterruptedException ignored) {}
    }

//    private final class Dispatcher implements Runnable {
//
//        @Override
//        public void run() {
//            while (!finished) {
//                try {
//                    selector.select(1000);
//                    final var selectedKeys = selector.selectedKeys();
//                    selectedKeys.forEach(key -> {
//                        try {
//                            final var channel = ssc.accept();
//                            channel.configureBlocking(false);
//                            // TODO: Or, I can use a separate selector for read operations and a separate
//                            //       thread. Which seems like it may be smarter. Somehow I want to compartmentalize
//                            //       all this nonsense, because having it all rammed into a single class is yucky.
//                            final var k2 = channel.register(selector, SelectionKey.OP_READ);
//                        } catch (IOException e) {
//                            throw new RuntimeException(e);
//                        }
//                    });
//                } catch (IOException ignored) {
////                    throw new RuntimeException(e);
//                }
//            }
//        }
//
//        void shutdown() {
//
//        }
//    }
}
