package com.hedera.hashgraph.web.impl.http2;

import java.util.Objects;

public class Http2Exception extends RuntimeException {
    private Http2ErrorCode code;

    public Http2Exception(Http2ErrorCode code) {
        this.code = Objects.requireNonNull(code);
    }
}
