package com.hedera.hashgraph.web.impl.http2;

public enum Http2ErrorCode {
    NO_ERROR,
    PROTOCOL_ERROR,
    INTERNAL_ERROR,
    FLOW_CONTROL_ERROR,
    SETTINGS_TIMEOUT,
    STREAM_CLOSED,
    FRAME_SIZE_ERROR,
    REFUSED_STREAM,
    CANCEL,
    COMPRESSION_ERROR,
    CONNECT_ERROR,
    ENHANCE_YOUR_CALM,
    INADEQUATE_SECURITY,
    HTTP_1_1_REQUIRED;

    @SuppressWarnings("DuplicatedCode")
    public static Http2ErrorCode fromOrdinal(int ordinal) {
        return switch (ordinal) {
            case 0 -> NO_ERROR;
            case 1 -> PROTOCOL_ERROR;
            case 2 -> INTERNAL_ERROR;
            case 3 -> FLOW_CONTROL_ERROR;
            case 4 -> SETTINGS_TIMEOUT;
            case 5 -> STREAM_CLOSED;
            case 6 -> FRAME_SIZE_ERROR;
            case 7 -> REFUSED_STREAM;
            case 8 -> CANCEL;
            case 9 -> COMPRESSION_ERROR;
            case 10 -> CONNECT_ERROR;
            case 11 -> ENHANCE_YOUR_CALM;
            case 12 -> INADEQUATE_SECURITY;
            case 13 -> HTTP_1_1_REQUIRED;
            default -> throw new IllegalArgumentException("Unknown ordinal " + ordinal);
        };
    }
}
