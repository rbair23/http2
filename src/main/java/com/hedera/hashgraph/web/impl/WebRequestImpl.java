package com.hedera.hashgraph.web.impl;

import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.WebRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class WebRequestImpl implements WebRequest {

    private final Dispatcher.RequestData requestData;
    private final HeadersEvent onHeaders;
    private WebHeaders responseHeaders;
    private int responseCode;
    private RequestDataOutputStream outputStream;

    public WebRequestImpl(Dispatcher.RequestData requestData, HeadersEvent onHeaders) {
        this.requestData = Objects.requireNonNull(requestData);
        this.onHeaders = onHeaders;
    }

    @Override
    public WebHeaders getRequestHeaders() {
        return requestData.getHeaders();
    }

    @Override
    public String getMethod() {
        return requestData.getMethod();
    }

    @Override
    public String getPath() {
        return requestData.getPath();
    }

    @Override
    public String getProtocol() {
        // TODO maybe rename getVersion to getProtocol? HTTP/1.1
        return requestData.getVersion();
    }

    @Override
    public InputStream getRequestBody() throws IOException {
        final var inputStream = new ByteArrayInputStream(requestData.getData(), 0, requestData.getDataLength());
        final var headers = requestData.getHeaders();
        final var contentEncoding = headers.getContentEncoding();
        if (contentEncoding != null && contentEncoding.contains(WebHeaders.CONTENT_ENCODING_GZIP)) {
            return new GZIPInputStream(inputStream);
        } else {
            return inputStream;
        }
    }

    @Override
    public void respond(WebHeaders responseHeaders, int responseCode) throws IOException {
        if (this.responseHeaders != null) {
            // Somebody is trying to respond twice!!
            throw new IOException("Cannot respond twice to the same request");
        }

        this.responseHeaders = Objects.requireNonNull(responseHeaders);
        this.responseCode = responseCode;
        if (onHeaders != null) {
            onHeaders.onResponseHeaders(responseHeaders, responseCode);
        }
    }

    @Override
    public OutputStream startResponse(WebHeaders responseHeaders, int responseCode) throws IOException {
        this.respond(responseHeaders, responseCode);
        requestData.setDataLength(0);
        outputStream = new RequestDataOutputStream(requestData);
        final var contentEncoding = responseHeaders.getContentEncoding();
        if (contentEncoding != null && contentEncoding.contains(WebHeaders.CONTENT_ENCODING_GZIP)) {
            return new GZIPOutputStream(outputStream);
        } else {
            return outputStream;
        }
    }

    @Override
    public void close() {
        if (outputStream != null) {
            requestData.setDataLength(outputStream.getCount());
        }
    }

    public Dispatcher.RequestData getRequestData() {
        return requestData;
    }

    public WebHeaders getResponseHeaders() {
        return responseHeaders;
    }

    public int getResponseCode() {
        return responseCode;
    }

    public interface HeadersEvent {
        void onResponseHeaders(final WebHeaders headers, int responseCode) throws IOException;
    }
}
