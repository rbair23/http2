package com.hedera.hashgraph.web.impl;

import com.hedera.hashgraph.web.ResponseAlreadySentException;
import com.hedera.hashgraph.web.StatusCode;
import com.hedera.hashgraph.web.WebRequest;
import com.hedera.hashgraph.web.WebRoutes;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * The {@code Dispatcher} is responsible for dispatching a {@link WebRequest} to a
 * {@link com.hedera.hashgraph.web.WebRoute}, if one is available that matches the path of the {@link WebRequest}.
 * It uses a thread in the provided {@link ExecutorService} to execute the request, blocking if there are
 * not any available threads.
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
                        webRequest.respond(StatusCode.INTERNAL_SERVER_ERROR_500);
                    } catch (ResponseAlreadySentException e) {
                        throw new RuntimeException(e);
                    }
                    ex.printStackTrace();
                }
            });
        } else {
            // Dude, 404
            try {
                webRequest.respond(StatusCode.NOT_FOUND_404);
            } catch (ResponseAlreadySentException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
