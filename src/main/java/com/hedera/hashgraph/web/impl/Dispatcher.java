package com.hedera.hashgraph.web.impl;

import com.hedera.hashgraph.web.ResponseAlreadySentException;
import com.hedera.hashgraph.web.StatusCode;
import com.hedera.hashgraph.web.WebRequest;
import com.hedera.hashgraph.web.WebRoutes;
import com.hedera.hashgraph.web.impl.session.ConnectionContext;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * The {@code Dispatcher} coordinates the work of the {@link ChannelManager} (which handles all networking
 * connections with the clients), the {@link ProtocolBase}s, and the data buffers used by the protocol
 * handlers. It also manages the {@link ExecutorService} (or thread pool) to which
 * {@link WebRequest}s are sent. There is a single instance of this class per
 * running {@link com.hedera.hashgraph.web.WebServer}, and it executes on a single thread.
 */
public final class Dispatcher {

    /**
     * The web server routes. This will never be null.
     */
    private final WebRoutes routes;


    /**
     * A thread pool for submitting work for dispatching for web handlers. This will never be null.
     */
    private final ExecutorService threadPool;

    /**
     * Create a new instance.
     *
     * @param routes The routes for handling a request. Cannot be null.
     * @param threadPool The pool of threads to use for handling a request. Cannot be null.
     */
    public Dispatcher(final WebRoutes routes, final ExecutorService threadPool){
        this.routes = Objects.requireNonNull(routes);
        this.threadPool = Objects.requireNonNull(threadPool);
    }

    /**
     * Called to dispatch a web request to be handled
     *
     * @param channelSession The {@link ConnectionContext} instance associated with the request to dispatch. Not null or closed.
     * @param webRequest The {@link WebRequest} to dispatch. Not null or closed.
     */
    public void dispatch(WebRequest webRequest) {
        // This callback is invoked when it is time to submit the request.
        // Can I get method and path from the headers?
        final var method = webRequest.getMethod();
        final var path = webRequest.getPath();
        final var handler = routes.match(method, path);
        if (handler != null) {
            threadPool.submit(() -> {
                try {
                    handler.handle(webRequest);
                } catch (Exception ex) {
                    // Oh dang, some exception happened while handling the request. Oof. Well, somebody
                    // needs to send a 500 error.
                    try {
                        webRequest.respond(StatusCode.INTERNAL_SERVER_ERROR);
                    } catch (ResponseAlreadySentException e) {
                        throw new RuntimeException(e);
                    }
                    ex.printStackTrace();
                }
            });
        } else {
            // Dude, 404
            try {
                webRequest.respond(StatusCode.NOT_FOUND);
            } catch (ResponseAlreadySentException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
