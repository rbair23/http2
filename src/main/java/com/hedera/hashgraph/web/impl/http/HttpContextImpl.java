package com.hedera.hashgraph.web.impl.http;

import com.hedera.hashgraph.web.WebServer;
import com.sun.net.httpserver.*;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class HttpContextImpl extends HttpContext {
    private HttpHandler handler;
    private final String path;
    private final WebServer server;
    private final Map<String,Object> attributes = new ConcurrentHashMap<>();
    private final List<Filter> filters = new CopyOnWriteArrayList<>();

    public HttpContextImpl(WebServer server, String path, HttpHandler handler) {
        this.server = Objects.requireNonNull(server);
        this.path = Objects.requireNonNull(path);
        this.handler = Objects.requireNonNull(handler);
    }

    public HttpContextImpl(WebServer server, String path) {
        this.server = Objects.requireNonNull(server);
        this.path = Objects.requireNonNull(path);
    }

    @Override
    public HttpHandler getHandler() {
        return handler;
    }

    @Override
    public void setHandler(HttpHandler handler) {
        // TODO I don't like this. Can I just make handler a hard requirement in the constructor? Maybe not?
        if (this.handler != null) {
            throw new IllegalArgumentException ("handler already set");
        }
        this.handler = Objects.requireNonNull(handler);
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public WebServer getServer() {
        return server;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @Override
    public List<Filter> getFilters() {
        return filters;
    }

    @Override
    public Authenticator setAuthenticator(Authenticator auth) {
        return null;
    }

    @Override
    public Authenticator getAuthenticator() {
        return null;
    }
}
