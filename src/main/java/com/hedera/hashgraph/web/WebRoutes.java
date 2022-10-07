package com.hedera.hashgraph.web;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class WebRoutes {
    /**
     * A concurrent map of (method-path)-to-handler. The {@link WebRequestHandler} will be invoked to handle
     * any HTTP request for the specific method and path. So every combination of method and path can
     * have at most one handler. This class is threadsafe, so the routes must be threadsafe as well.
     */
    private final Map<String, WebRequestHandler> routes = new ConcurrentHashMap<>();

    public WebRoute get(String path, WebRequestHandler handler) {
        return route("GET", path, handler);
    }

    public WebRoute put(String path, WebRequestHandler handler) {
        return route("PUT", path, handler);
    }

    public WebRoute post(String path, WebRequestHandler handler) {
        return route("POST", path, handler);
    }

    public WebRoute delete(String path, WebRequestHandler handler) {
        return route("DELETE", path, handler);
    }

    public WebRoute head(String path, WebRequestHandler handler) {
        return route("HEAD", path, handler);
    }

    public WebRoute options(String path, WebRequestHandler handler) {
        return route("OPTIONS", path, handler);
    }

    public WebRoute connect(String path, WebRequestHandler handler) {
        return route("CONNECT", path, handler);
    }

    public WebRoute trace(String path, WebRequestHandler handler) {
        return route("TRACE", path, handler);
    }

    public WebRoute patch(String path, WebRequestHandler handler) {
        return route("PATCH", path, handler);
    }

    public WebRoute route(String method, String path, WebRequestHandler handler) {
        Objects.requireNonNull(method);
        Objects.requireNonNull(path);
        Objects.requireNonNull(handler);

        final var m = method.toUpperCase();
        final var key = m + "-" + path;
        routes.put(key, handler);
        return new WebRoute(m, path, handler);
    }

    public void removeRoute(WebRoute routeToRemove) throws IllegalArgumentException {
        Objects.requireNonNull(routeToRemove);
        routes.remove(routeToRemove.method() + "-" + routeToRemove.path());
    }
}
