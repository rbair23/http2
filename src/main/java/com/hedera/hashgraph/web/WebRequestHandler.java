package com.hedera.hashgraph.web;

import java.io.IOException;

/**
 * The callback method registered for listening to a specific path on the {@link WebServer}'s,
 * {@link WebRoutes}.
 */
public interface WebRequestHandler {
    /**
     * Called to handle a request. The calling thread will be one in the pool supplied in the
     * {@link WebServerConfig}'s {@link java.util.concurrent.ExecutorService}. Each time this
     * handler is called, it may be called from a different thread. It may be called concurrently
     * by multiple threads.
     *
     * <p>The {@link WebRequest} contains all information related to the request, and is used
     * to generate a {@link WebResponse}.
     *
     * @param request The request. Will not be null.
     * @throws IOException If for some reason the application is unable to respond (such as a
     *                     closed connection).
     */
    void handle(WebRequest request) throws IOException;
}
