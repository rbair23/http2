package com.hedera.hashgraph.web;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiConsumer;

public final class WebHeaders {
    public static final String CONTENT_TYPE_PLAIN_TEXT = "/text/plain";
    public static final String CONTENT_TYPE_HTML = "text/html; charset=UTF-8";
    public static final String CONTENT_TYPE_JSON = "application/json";

    public static final String CONTENT_ENCODING_GZIP = "gzip";
    public static final String CONTENT_ENCODING_COMPRESS = "compress";
    public static final String CONTENT_ENCODING_DEFLATE = "deflate";

    /** Formatter for http header dates */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern(
            "EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            .withZone(ZoneId.of("GMT"));

    private final Map<String, String> headers = new HashMap<>();

    public void put(String key, String value) {
        headers.put(key, value);
    }

    /**
     * Put a date header value
     *
     * @param key header key
     * @param date header value instant
     */
    public void put(String key, Instant date) {
        headers.put(key, FORMATTER.format(date));
    }

    public String get(String key) {
        return headers.get(key);
    }

    public void clear() {
        headers.clear();
    }

    public List<String> getAsList(String key) {
        final var value = headers.get(key);
        if (value == null) {
            return null;
        }

        final List<String> list = new ArrayList<>();
        final var elements = value.split(",");
        for (var element : elements) {
            list.add(element.trim());
        }
        return list;
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
