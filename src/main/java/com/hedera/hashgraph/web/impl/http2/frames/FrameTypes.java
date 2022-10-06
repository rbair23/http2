package com.hedera.hashgraph.web.impl.http2.frames;

// The ordinals on these exactly align with the flags sent on the wire
public enum FrameTypes {
    DATA,
    HEADERS,
    PRIORITY,
    RST_STREAM,
    SETTINGS,
    PUSH_PROMISE,
    PING,
    GO_AWAY,
    WINDOW_UPDATE,
    CONTINUATION;

    public static FrameTypes fromOrdinal(int ordinal) {
        return switch (ordinal) {
            case 0 -> DATA;
            case 1 -> HEADERS;
            case 2 -> PRIORITY;
            case 3 -> RST_STREAM;
            case 4 -> SETTINGS;
            case 5 -> PUSH_PROMISE;
            case 6 -> PING;
            case 7 -> GO_AWAY;
            case 8 -> WINDOW_UPDATE;
            case 9 -> CONTINUATION;
            default -> throw new IllegalArgumentException("Unknown ordinal " + ordinal);
        };
    }
}
