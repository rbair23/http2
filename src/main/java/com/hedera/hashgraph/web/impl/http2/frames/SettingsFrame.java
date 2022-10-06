package com.hedera.hashgraph.web.impl.http2.frames;

/*

 */
public final class SettingsFrame extends Frame {
    public SettingsFrame(int streamId) {
        super(FrameTypes.SETTINGS, streamId);
    }
}
