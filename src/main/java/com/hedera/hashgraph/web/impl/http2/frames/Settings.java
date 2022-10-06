package com.hedera.hashgraph.web.impl.http2.frames;

public enum Settings {
    SETTINGS_HEADER_TABLE_SIZE,
    SETTINGS_ENABLE_PUSH,
    SETTINGS_MAX_CONCURRENT_STREAMS,
    SETTINGS_INITIAL_WINDOW_SIZE,
    SETTINGS_MAX_FRAME_SIZE,
    SETTINGS_MAX_HEADER_LIST_SIZE;

    public static Settings fromOrdinal(int ordinal) {
        return switch (ordinal) {
            case 0 -> SETTINGS_HEADER_TABLE_SIZE;
            case 1 -> SETTINGS_ENABLE_PUSH;
            case 2 -> SETTINGS_MAX_CONCURRENT_STREAMS;
            case 3 -> SETTINGS_INITIAL_WINDOW_SIZE;
            case 4 -> SETTINGS_MAX_FRAME_SIZE;
            case 5 -> SETTINGS_MAX_HEADER_LIST_SIZE;
            default -> null;
        };
    }
}
