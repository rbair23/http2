package com.hedera.hashgraph.web.impl;

import com.hedera.hashgraph.web.StatusCode;
import com.hedera.hashgraph.web.WebRequest;
import com.hedera.hashgraph.web.WebResponse;
import com.hedera.hashgraph.web.WebRoutes;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

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
     * @param webRequest  The {@link WebRequest} to dispatch. Not null or closed.
     * @param webResponse The response used for setting what response should be sent to client
     */
    public Future<?> dispatch(WebRequest webRequest, WebResponse webResponse) {
        // This callback is invoked when it is time to submit the request.
        // Can I get method and path from the headers?
        final var method = webRequest.getMethod();
        final var path = webRequest.getPath();
        final var handler = routes.match(method, path);
        if (handler != null) {
            return threadPool.submit(() -> {
                try {
                    handler.handle(webRequest, webResponse);
                } catch (Exception ex) {
                    // Oh dang, some exception happened while handling the request. Oof. Well, somebody
                    // needs to send a 500 error.
                    webResponse.respond(StatusCode.INTERNAL_SERVER_ERROR_500);
                    // TODO for now print error to aid debugging
                    ex.printStackTrace();
                } finally {
                    try {
                        webResponse.close();
                    } catch (Exception ignored) {
                    }
                }
            });
        } else {
            // Dude, 404
            webResponse.respond(StatusCode.NOT_FOUND_404);
            final var future = new CompletableFuture<>();
            future.complete(null);
            return future;
        }
    }
}
