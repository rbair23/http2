package com.hedera.hashgraph.web;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

public final class WebHeaders {
    public static final String CONTENT_TYPE_PLAIN_TEXT = "/text/plain";
    public static final String CONTENT_TYPE_HTML = "text/html; charset=UTF-8";
    public static final String CONTENT_TYPE_JSON = "application/json";

    public static final String CONTENT_ENCODING_GZIP = "gzip";
    public static final String CONTENT_ENCODING_COMPRESS = "compress";
    public static final String CONTENT_ENCODING_DEFLATE = "deflate";

    private final Map<String, String> headers = new HashMap<>();

    public void put(String key, String value) {
        headers.put(key, value);
    }

    public String get(String key) {
        return headers.get(key);
    }

    public void forEach(BiConsumer<String, String> callback) {
        headers.forEach(callback);
    }

    // A bunch of methods for common HTTP response headers
    public WebHeaders setContentType(String contentType) {
        put("Content-Type", Objects.requireNonNull(contentType));
        return this;
    }

    public String getContentType() {
        return get("Content-Type");
    }

    public WebHeaders setContentEncoding(String contentEncoding) {
        put("Content-Encoding", Objects.requireNonNull(contentEncoding));
        return this;
    }

    public WebHeaders setContentEncoding(String... contentEncoding) {
        put("Content-Encoding", String.join(", ", contentEncoding));
        return this;
    }

    public String getContentEncoding() {
        return get("Content-Encoding");
    }

    public WebHeaders setContentLength(int contentLength) {
        if (contentLength < 0) {
            throw new IllegalArgumentException("Cannot have a content length of less than 0");
        }

        put("Content-Length", "" + contentLength);
        return this;
    }

    public int getContentLength() {
        final var contentLength = get("Content-Length");
        return contentLength == null ? -1 : Integer.parseInt(contentLength);
    }

    public WebHeaders setServer(String server) {
        put("Server", Objects.requireNonNull(server));
        return this;
    }

    public String getServer() {
        return get("Server");
    }
}
