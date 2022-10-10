package com.hedera.hashgraph.web.impl.http2;

import java.util.Objects;

public final class Http2Exception extends RuntimeException {
    private final Http2ErrorCode code;
    private final int streamId;

    public Http2Exception(Http2ErrorCode code, int streamId) {
        this.code = Objects.requireNonNull(code);
        this.streamId = streamId;
    }

    public Http2ErrorCode getCode() {
        return code;
    }

    public int getStreamId() {
        return streamId;
    }
}
