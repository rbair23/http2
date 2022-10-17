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

    public void putMethod(String method) {
        pseudoHeaders.put(":method", method);
    }

    public String getScheme() {
        return pseudoHeaders.get(":scheme");
    }

    public void putScheme(String scheme) {
        pseudoHeaders.put(":scheme", scheme);
    }

    public String getAuthority() {
        return pseudoHeaders.get(":authority");
    }

    public void putAuthority(String authority) {
        pseudoHeaders.put(":authority", authority);
    }

    public String getPath() {
        return pseudoHeaders.get(":path");
    }

    public void putPath(String path) {
        pseudoHeaders.put(":path", path);
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

    public void putStatus(StatusCode status) {
        pseudoHeaders.put(":status", "" + status.code());
    }

    @Override
    public void clear() {
        super.clear();
        this.pseudoHeaders.clear();
    }
}
