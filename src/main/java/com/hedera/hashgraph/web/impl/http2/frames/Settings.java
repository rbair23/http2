package com.hedera.hashgraph.web.impl.http2.frames;

public final class Settings {
    // These values come from the spec
    public static final int INITIAL_FRAME_SIZE = 1 << 14;
    public static final int MAX_FRAME_SIZE = (1 << 24) - 1;
    public static final int MAX_FLOW_CONTROL_WINDOW_SIZE = (1 << 31) - 1;

    // Default sizes come from the spec, section 6.5.2
    public static final int DEFAULT_HEADER_TABLE_SIZE = 1 << 12;
    public static final boolean DEFAULT_ENABLE_PUSH = false;
    public static final int DEFAULT_MAX_CONCURRENT_STREAMS = Integer.MAX_VALUE;
    public static final int DEFAULT_INITIAL_WINDOW_SIZE = (1 << 16) - 1;
    public static final int DEFAULT_MAX_HEADER_FRAME_SIZE = Integer.MAX_VALUE;
    public static final int DEFAULT_MAX_FRAME_SIZE = INITIAL_FRAME_SIZE;

    private long headerTableSize = DEFAULT_HEADER_TABLE_SIZE;
    private boolean enablePush = DEFAULT_ENABLE_PUSH;
    private long maxConcurrentStreams = DEFAULT_MAX_CONCURRENT_STREAMS;
    private long initialWindowSize = DEFAULT_INITIAL_WINDOW_SIZE;
    private int maxFrameSize = INITIAL_FRAME_SIZE;
    private long maxHeaderListSize = DEFAULT_MAX_HEADER_FRAME_SIZE;

    public Settings() {

    }

    public long getHeaderTableSize() {
        return headerTableSize;
    }

    public void setHeaderTableSize(long headerTableSize) {
        this.headerTableSize = headerTableSize;
    }

    public boolean isEnablePush() {
        return enablePush;
    }

    public void setEnablePush(boolean enablePush) {
        this.enablePush = enablePush;
    }

    public long getMaxConcurrentStreams() {
        return maxConcurrentStreams;
    }

    public void setMaxConcurrentStreams(long maxConcurrentStreams) {
        this.maxConcurrentStreams = maxConcurrentStreams;
    }

    public long getInitialWindowSize() {
        return initialWindowSize;
    }

    public void setInitialWindowSize(long initialWindowSize) {
        this.initialWindowSize = initialWindowSize;
    }

    public int getMaxFrameSize() {
        return maxFrameSize;
    }

    public void setMaxFrameSize(int maxFrameSize) {
        this.maxFrameSize = maxFrameSize;
    }

    public long getMaxHeaderListSize() {
        return maxHeaderListSize;
    }

    public void setMaxHeaderListSize(long maxHeaderListSize) {
        this.maxHeaderListSize = maxHeaderListSize;
    }
}
