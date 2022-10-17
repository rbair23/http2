package com.hedera.hashgraph.web;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

public interface WebHeaders {
    DateTimeFormatter HEADER_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH)
                    .withZone(ZoneId.of("UTC"));


    String CONTENT_TYPE_PLAIN_TEXT = "/text/plain";
    String CONTENT_TYPE_HTML = "text/html; charset=UTF-8";
    String CONTENT_TYPE_JSON = "application/json";

    String CONTENT_ENCODING_GZIP = "gzip";
    String CONTENT_ENCODING_COMPRESS = "compress";
    String CONTENT_ENCODING_DEFLATE = "deflate";

    WebHeaders put(String key, String value);
    WebHeaders put(String key, Instant date);

    String get(String key);

    List<String> getAsList(String key);

    Set<String> keySet();

    void forEach(BiConsumer<String, String> callback);

    // A bunch of methods for common HTTP response headers
    default WebHeaders setContentType(String contentType) {
        put("content-type", Objects.requireNonNull(contentType));
        return this;
    }

    default String getContentType() {
        return get("content-type");
    }

    default WebHeaders setContentEncoding(String contentEncoding) {
        put("content-encoding", Objects.requireNonNull(contentEncoding));
        return this;
    }

    default WebHeaders setContentEncoding(String... contentEncoding) {
        put("content-encoding", String.join(", ", contentEncoding));
        return this;
    }

    default String getContentEncoding() {
        return get("content-encoding");
    }

    default WebHeaders setContentLength(int contentLength) {
        if (contentLength < 0) {
            throw new IllegalArgumentException("Cannot have a content length of less than 0");
        }

        put("content-length", "" + contentLength);
        return this;
    }

    default int getContentLength() {
        final var contentLength = get("content-length");
        return contentLength == null ? -1 : Integer.parseInt(contentLength);
    }

    default WebHeaders setServer(String server) {
        put("server", Objects.requireNonNull(server));
        return this;
    }

    default String getServer() {
        return get("server");
    }

    default WebHeaders setDateToNow() {
        put("date", HEADER_DATE_FORMATTER.format(Instant.now()));
        return this;
    }

    default WebHeaders setNoCache() {
        put("expires", HEADER_DATE_FORMATTER.format(Instant.now()));
        put("cache-control", "max-age=0, no-cache, no-store");
        return this;
    }

    default WebHeaders setStandardResponseHeaders() {
        setDateToNow();
        setNoCache();
        setServer(WebServer.SERVER_NAME);
        put("connection","keep-alive"); // TODO ?
        put("x-robots-tag","noindex"); // TODO ?
        return this;
    }

    default boolean getKeepAlive() {
        final String connectionHeader = get("connection");
        return connectionHeader != null && connectionHeader.toLowerCase().contains("keep-alive");
    }

}
