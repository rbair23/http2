package com.hedera.hashgraph.web.impl;

import com.hedera.hashgraph.web.WebHeaders;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public class WebHeadersImpl implements WebHeaders {
    /** Formatter for http header dates */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern(
                    "EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            .withZone(ZoneId.of("GMT"));

    private final Map<String, String> headers = new HashMap<>();

    public final WebHeaders put(String key, String value) {
        headers.put(key.toLowerCase(), value);
        return this;
    }

    /**
     * Put a date header value
     *
     * @param key header key
     * @param date header value instant
     */
    public final WebHeaders put(String key, Instant date) {
        return put(key, FORMATTER.format(date));
    }

    public final String get(String key) {
        return headers.get(key);
    }

    public void clear() {
        headers.clear();
    }

    public final List<String> getAsList(String key) {
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

    public final Set<String> keySet() {
        return headers.keySet();
    }

    public final void forEach(BiConsumer<String, String> callback) {
        headers.forEach(callback);
    }

    @Override
    public String toString() {
        return "WebHeaders {" +
                headers.entrySet().stream()
                        .map(entry -> entry.getKey()+": "+entry.getValue())
                        .collect(Collectors.joining(", ")) +
                '}';
    }
}
