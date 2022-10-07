package com.hedera.hashgraph.web.impl;

import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.WebRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * In HTTP/1.0 or HTTP/1.1, there is one "stream" per connection. In HTTP/2.0, there may be
 * multiple "streams" per connection. This data is per stream, rather than per connection.
 */
final class WebRequestImpl implements WebRequest {
    private enum State {
        COLLECTING_HEADERS,
        COLLECTING_BODY,
        READY
    }

    private WebRequestImpl.State state = WebRequestImpl.State.COLLECTING_HEADERS;
    private WebHeaders requestHeaders;
    private byte[] headerData = new byte[1024];
    private byte[] bodyData = new byte[1024 * 6];
    private int bodyLength = 0;

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
