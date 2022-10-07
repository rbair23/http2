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
public final class WebRequestImpl implements WebRequest {
    private WebHeaders requestHeaders;

    public WebRequestImpl(WebHeaders requestHeaders) {
        this.requestHeaders = Objects.requireNonNull(requestHeaders);
    }

    @Override
    public void close() {

    }

    @Override
    public WebHeaders getRequestHeaders() {
        return requestHeaders;
    }

    void reset() {

    }

    @Override
    public WebHeaders getResponseHeaders() {
        return null;
    }

    @Override
    public String getPath() {
        return null;
    }

    @Override
    public String getRequestMethod() {
        return null;
    }

    @Override
    public InputStream getRequestBody() {
        return null;
    }

    @Override
    public OutputStream getResponseBody() {
        return null;
    }

    @Override
    public void sendResponseHeaders(int rCode, long responseLength) throws IOException {

    }

    @Override
    public int getResponseCode() {
        return 0;
    }

    @Override
    public String getProtocol() {
        return null;
    }
}
