package com.hedera.hashgraph.web.impl.http2.frames;

public final class ContinuationFrame extends Frame {

    /**
     * The compressed data
     */
    private final byte[] fieldBlockFragment;

    public ContinuationFrame(byte flags, int streamId, byte[] data) {
        super(data.length, FrameType.CONTINUATION, flags, streamId);
        this.fieldBlockFragment = data;
    }

    public boolean isEndHeaders() {
        return isSixthFlagSet();
    }

    public byte[] getFieldBlockFragment() {
        return fieldBlockFragment;
    }
}
