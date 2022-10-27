package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.StatusCode;
import com.hedera.hashgraph.web.impl.WebHeadersImpl;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class Http2Headers extends WebHeadersImpl {
    private final Map<String, String> pseudoHeaders = new HashMap<>();

    public Http2Headers() {
    }

    public void putPseudoHeader(String key, String value) {
        this.pseudoHeaders.put(key, value);
    }

    public String getPseudoHeader(String key) {
        return pseudoHeaders.get(key);
    }

    public Set<String> pseudoHeaderKeySet() {
        return pseudoHeaders.keySet();
    }

    public String getMethod() {
        return pseudoHeaders.get(":method");
    }

    public Http2Headers putMethod(String method) {
        pseudoHeaders.put(":method", method);
        return this;
    }

    public String getScheme() {
        return pseudoHeaders.get(":scheme");
    }

    public Http2Headers putScheme(String scheme) {
        pseudoHeaders.put(":scheme", scheme);
        return this;
    }

    public String getAuthority() {
        return pseudoHeaders.get(":authority");
    }

    public Http2Headers putAuthority(String authority) {
        pseudoHeaders.put(":authority", authority);
        return this;
    }

    public String getPath() {
        return pseudoHeaders.get(":path");
    }

    public Http2Headers putPath(String path) {
        pseudoHeaders.put(":path", path);
        return this;
    }

    public StatusCode getStatus() {
        final var s = pseudoHeaders.get(":status");
        if (s == null) {
            return null;
        } else {
            try {
                final var code = Integer.parseInt(s);
                return StatusCode.forCode(code);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    public Http2Headers putStatus(StatusCode status) {
        pseudoHeaders.put(":status", "" + status.code());
        return this;
    }

    @Override
    public void clear() {
        super.clear();
        this.pseudoHeaders.clear();
    }
}
