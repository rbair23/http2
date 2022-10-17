package com.hedera.hashgraph.web.impl.http2.frames;

public final class Settings {
    // These values come from the spec

    /**
     * SPEC: 4.2 Frame Size
     *
     * <p>The size of a frame payload is limited by the maximum size that a receiver advertises in the
     * SETTINGS_MAX_FRAME_SIZE setting. This setting can have any value between 2^14 (16,384) and (2^24)-1
     * (16,777,215) octets, inclusive.
     */
    public static final int INITIAL_FRAME_SIZE = 1 << 14;

    /**
     * SPEC: 4.2 Frame Size
     *
     * <p>All implementations MUST be capable of receiving and minimally processing frames up to 2^14 octets in
     * length, plus the 9-octet frame header (Section 4.1). The size of the frame header is not included when
     * describing frame sizes.
     */
    public static final int INITIAL_FRAME_BUFFER_SIZE = INITIAL_FRAME_SIZE + Frame.FRAME_HEADER_SIZE;

    /**
     * SPEC: 4.2 Frame Size
     *
     * <p>The size of a frame payload is limited by the maximum size that a receiver advertises in the
     * SETTINGS_MAX_FRAME_SIZE setting. This setting can have any value between 2^14 (16,384) and (2^24)-1
     * (16,777,215) octets, inclusive.
     */
    public static final int MAX_FRAME_SIZE = (1 << 24) - 1;

    /**
     * SPEC: 4.2 Frame Size
     *
     * <p>All implementations MUST be capable of receiving and minimally processing frames up to 2^14 octets in
     * length, plus the 9-octet frame header (Section 4.1). The size of the frame header is not included when
     * describing frame sizes.
     */
    public static final int MAX_FRAME_BUFFER_SIZE = MAX_FRAME_SIZE + Frame.FRAME_HEADER_SIZE;

    /**
     * SPEC: 6.5.2 Defined Settings
     *
     * <p>... the maximum flow-control window size of (2^31)-1 ...
     */
    public static final int MAX_FLOW_CONTROL_WINDOW_SIZE = (1 << 31) - 1;

    /**
     * SPEC: 6.5.2 Defined Settings
     *
     * <p>This setting allows the sender to inform the remote endpoint of the maximum size of the compression table used
     * to decode field blocks, in units of octets. The encoder can select any size equal to or less than this value by
     * using signaling specific to the compression format inside a field block (see [COMPRESSION]). The initial value
     * is 4,096 octets
     */
    public static final int DEFAULT_HEADER_TABLE_SIZE = 4096;

    /**
     * SPEC: 6.5.2 Defined Settings
     *
     * <p>This setting can be used to enable or disable server push. A server MUST NOT send a PUSH_PROMISE frame if it
     * receives this parameter set to a value of 0; see Section 8.4. A client that has both set this parameter to 0 and
     * had it acknowledged MUST treat the receipt of a PUSH_PROMISE frame as a connection error (Section 5.4.1) of type
     * PROTOCOL_ERROR.
     *
     * <p>The initial value of SETTINGS_ENABLE_PUSH is 1. For a client, this value indicates that it is willing to
     * receive PUSH_PROMISE frames. For a server, this initial value has no effect, and is equivalent to the value 0.
     * Any value other than 0 or 1 MUST be treated as a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
     *
     * <p>A server MUST NOT explicitly set this value to 1. A server MAY choose to omit this setting when it sends a
     * SETTINGS frame, but if a server does include a value, it MUST be 0. A client MUST treat receipt of a SETTINGS
     * frame with SETTINGS_ENABLE_PUSH set to 1 as a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
     */
    public static final boolean DEFAULT_ENABLE_PUSH = false;

    /**
     * SPEC: 6.5.2 Defined Settings
     *
     * <p>This setting indicates the maximum number of concurrent streams that the sender will allow. This limit is
     * directional: it applies to the number of streams that the sender permits the receiver to create. Initially,
     * there is no limit to this value. It is recommended that this value be no smaller than 100, so as to not
     * unnecessarily limit parallelism.
     *
     * <p>A value of 0 for SETTINGS_MAX_CONCURRENT_STREAMS SHOULD NOT be treated as special by endpoints. A zero
     * value does prevent the creation of new streams; however, this can also happen for any limit that is exhausted
     * with active streams. Servers SHOULD only set a zero value for short durations; if a server does not wish to
     * accept requests, closing the connection is more appropriate.
     */
    public static final int DEFAULT_MAX_CONCURRENT_STREAMS = Integer.MAX_VALUE;

    /**
     * SPEC: 6.5.2 Defined Settings
     *
     * <p>This setting indicates the sender's initial window size (in units of octets) for stream-level flow control.
     * The initial value is (2^16)-1 (65,535) octets.
     *
     * <p>This setting affects the window size of all streams (see Section 6.9.2).
     *
     * <p>Values above the maximum flow-control window size of (2^31)-1 MUST be treated as a connection error
     * (Section 5.4.1) of type FLOW_CONTROL_ERROR.
     */
    public static final int DEFAULT_INITIAL_WINDOW_SIZE = 65535;

    /**
     * SPEC: 6.5.2 Defined Settings
     *
     * <p>This setting indicates the size of the largest frame payload that the sender is willing to receive, in units
     * of octets.
     *
     * <p>The initial value is 2^14 (16,384) octets. The value advertised by an endpoint MUST be between this initial
     * value and the maximum allowed frame size ((2^24)-1 or 16,777,215 octets), inclusive. Values outside this range
     * MUST be treated as a connection error (Section 5.4.1) of type PROTOCOL_ERROR
     */
    public static final int DEFAULT_MAX_FRAME_SIZE = 16384;

    /**
     * SPEC: 6.5.2 Defined Settings
     *
     * <p>This advisory setting informs a peer of the maximum field section size that the sender is prepared to accept,
     * in units of octets. The value is based on the uncompressed size of field lines, including the length of the name
     * and value in units of octets plus an overhead of 32 octets for each field line.
     *
     * <p>For any given request, a lower limit than what is advertised MAY be enforced. The initial value of this
     * setting is unlimited.
     */
    public static final int DEFAULT_MAX_HEADER_LIST_SIZE = Integer.MAX_VALUE;

    private long headerTableSize;
    private boolean enablePush;
    private long maxConcurrentStreams;
    private long initialWindowSize;
    private int maxFrameSize;
    private long maxHeaderListSize;

    public Settings() {
        resetToDefaults();
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

    public void resetToDefaults() {
        headerTableSize = DEFAULT_HEADER_TABLE_SIZE;
        enablePush = DEFAULT_ENABLE_PUSH;
        maxConcurrentStreams = DEFAULT_MAX_CONCURRENT_STREAMS;
        initialWindowSize = DEFAULT_INITIAL_WINDOW_SIZE;
        maxFrameSize = INITIAL_FRAME_SIZE;
        maxHeaderListSize = DEFAULT_MAX_HEADER_LIST_SIZE;
    }
}
