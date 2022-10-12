package com.hedera.hashgraph.web;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Configuration for the {@link WebServer}.
 *
 * @param addr The internet address the server is bound to. Cannot be null.
 * @param executor The executor used for handling a connection. This executor may be specified by the user.
 *                 By default, it is single-threaded, meaning that only a single connection can be handled at a time.
 * @param backlog The number of connections to hold in a backlog until they are ready to be handled.
 * @param noDelay The value of the TCP_NODELAY socket option
 */
public record WebServerConfig(
        InetSocketAddress addr,
        ExecutorService executor,
        int backlog,
        boolean noDelay,
        int maxIdleConnections,
        int maxRequestSize) {
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
    private static final int DEFAULT_MAX_IDLE_CONNECTIONS = 200 ;
    public static final int DEFAULT_MAX_REQUEST_SIZE = (1 << 14) + 128; // slightly more than 16K

    /*
        Additional configuration we may want
        private static final int DEFAULT_CLOCK_TICK = 10000 ; // 10 sec.

        // These values must be a reasonable multiple of clockTick
        private static final long DEFAULT_IDLE_INTERVAL = 30 ; // 5 min


        private static final long DEFAULT_MAX_REQ_TIME = -1; // default: forever
        private static final long DEFAULT_MAX_RSP_TIME = -1; // default: forever
        private static final long DEFAULT_TIMER_MILLIS = 1000;
        private static final int  DEFAULT_MAX_REQ_HEADERS = 200;
        private static final long DEFAULT_DRAIN_AMOUNT = 64 * 1024;

        private static int clockTick;
        private static long idleInterval;
        // The maximum number of bytes to drain from an inputstream
        private static long drainAmount;
        private static int maxIdleConnections;
        // The maximum number of request headers allowable
        private static int maxReqHeaders;
        // max time a request or response is allowed to take
        private static long maxReqTime;
        private static long maxRspTime;
        private static long timerMillis;
        private static boolean debug;

        // the value of the TCP_NODELAY socket-level option
        private static boolean noDelay;
     */


    public WebServerConfig {
        Objects.requireNonNull(addr);
        Objects.requireNonNull(executor);
    }

    public WebServerConfig() {
        this(new InetSocketAddress("localhost", 0),
                Executors.newSingleThreadExecutor(),
                0,
                true,
                DEFAULT_MAX_IDLE_CONNECTIONS,
                DEFAULT_MAX_REQUEST_SIZE);
    }

    public static final class Builder {
        private String host;
        private int port;
        private InetSocketAddress addr;
        private int backlog;
        private int maxIdleConnections = DEFAULT_MAX_IDLE_CONNECTIONS;
        private int maxRequestSize = DEFAULT_MAX_REQUEST_SIZE;
        private ExecutorService executor;
        private boolean noDelay;

        public Builder host(String host) {
            this.host = Objects.requireNonNull(host);
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder ephemeralPort() {
            this.port = WebServer.EPHEMERAL_PORT;
            return this;
        }

        public Builder address(InetSocketAddress address) {
            this.addr = address;
            return this;
        }

        public Builder backlog(int backlog) {
            this.backlog = backlog;
            return this;
        }

        public Builder maxIdleConnections(int maxIdleConnections) {
            if (maxIdleConnections < 1) {
                throw new IllegalArgumentException("You must have at least 1 idle connection");
            }
            this.maxIdleConnections = maxIdleConnections;
            return this;
        }

        public Builder maxRequestSize(int maxRequestSize) {
            if (maxRequestSize < 128) {
                throw new IllegalArgumentException("The maximum request size must be at least 128 bytes");
            }
            this.maxRequestSize = maxRequestSize;
            return this;
        }

        public Builder noDelay(boolean value) {
            this.noDelay = value;
            return this;
        }

        public Builder executor(ExecutorService e) {
            this.executor = e;
            return this;
        }

        public Builder reset() {
            this.host = "localhost";
            this.port = WebServer.EPHEMERAL_PORT;
            this.addr = null;
            this.backlog = DEFAULT_BACKLOG;
            this.noDelay = true;
            this.executor = null;
            return this;
        }

        public WebServerConfig build() {
            final var a = addr == null ? new InetSocketAddress(host, port) : addr;
            final var e = executor == null ? Executors.newSingleThreadExecutor() : executor;
            return new WebServerConfig(a, e, backlog, noDelay, maxIdleConnections, maxRequestSize);
        }
    }
}
