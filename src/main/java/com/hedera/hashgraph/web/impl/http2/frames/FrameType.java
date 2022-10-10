package com.hedera.hashgraph.web.impl.http2.frames;

/**
 * The type of frame. The ordinal values of these enums exactly align with the frame type flags
 * on the wire.
 */
public enum FrameType {
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

    /**
     * A convenience function for getting the enum value based on the ordinal.
     *
     * @param ordinal The ordinal value.
     * @return The enum value with the matching ordinal
     * @throws IllegalArgumentException if the ordinal is not a valid value
     */
    public static FrameType valueOf(int ordinal) {
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
            default -> throw new IllegalArgumentException("Unknown type " + ordinal);
        };
    }
}
