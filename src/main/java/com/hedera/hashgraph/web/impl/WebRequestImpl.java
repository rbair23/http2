package com.hedera.hashgraph.web.impl;

import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.WebRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/**
 * In HTTP/1.0 or HTTP/1.1, there is one "stream" per connection. In HTTP/2.0, there may be
 * multiple "streams" per connection. This data is per stream, rather than per connection.
 *
 * // TODO Turn into a record, this is not reused.
 */
public record WebRequestImpl(String method, String path, String version, String protocol, WebHeaders requestHeaders) implements WebRequest {
    public WebRequestImpl {
        Objects.requireNonNull(method);
        Objects.requireNonNull(path);
        Objects.requireNonNull(version);
        Objects.requireNonNull(requestHeaders);
    }

    @Override
    public WebHeaders getRequestHeaders() {
        return requestHeaders;
    }

    @Override
    public String getMethod() {
        return method;
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public String getProtocol() {
        return protocol;
    }

    @Override
    public InputStream getRequestBody() {
        return null;
    }

    @Override
    public void respond(WebHeaders responseHeaders, int responseCode) throws IOException {

    }

    @Override
    public OutputStream startResponse(WebHeaders responseHeaders, int responseCode) throws IOException {
        return null;
    }

    @Override
    public void close() {

    }
}
