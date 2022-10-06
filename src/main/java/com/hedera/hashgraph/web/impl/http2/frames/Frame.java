package com.hedera.hashgraph.web.impl.http2.frames;

public abstract class Frame {
    protected static final int FLAG_0 = 0b1000_0000;
    protected static final int FLAG_1 = 0b0100_0000;
    protected static final int FLAG_2 = 0b0010_0000;
    protected static final int FLAG_3 = 0b0001_0000;
    protected static final int FLAG_4 = 0b0000_1000;
    protected static final int FLAG_5 = 0b0000_0100;
    protected static final int FLAG_6 = 0b0000_0010;
    protected static final int FLAG_7 = 0b0000_0001;

    // all sizes are in *bits*

    // length (24)
    // sizes exceeding 16,384 MUST NOT BE SENT unless the receiver has set a larger valeu for SETTINGS_MAX_FRAME_SIZE
    // (but we don't need to care and can just limit ourselves to these sizes)

    // type (8)
    private FrameTypes type;

    // flags (8)
    private int flags;

    // Stream Identifier (31)
    private int streamId;

    protected Frame(FrameTypes type, int streamId) {
        this.type = type;
        this.streamId = streamId;
        if (streamId < 0) {
            throw new IllegalArgumentException("FrameID must not use the 32nd bit");
        }
    }

    protected void setFlag(int mask) {
        flags |= mask;
    }

    protected void clearFlag(int mask) {
        flags &= ~mask;
    }

    // reserved (1)
    // payload....


    public final int getStreamId() {
        return streamId;
    }
}
