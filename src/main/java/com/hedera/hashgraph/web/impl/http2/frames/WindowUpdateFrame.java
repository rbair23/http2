package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.util.HttpInputStream;
import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;

public final class WindowUpdateFrame extends Frame {

    /**
     * The window size increment value. Will never be 0.
     */
    private final int windowSizeIncrement;

    public WindowUpdateFrame(int streamId, int windowSizeIncrement) {
        super(4, FrameType.HEADERS, (byte) 0, streamId);
        this.windowSizeIncrement = windowSizeIncrement;
    }

    /**
     * Gets the window size increment
     *
     * @return The window size increment
     */
    public int getWindowSizeIncrement() {
        return windowSizeIncrement;
    }

    /**
     * Parses a WindowFrameUpdate from the input stream. The stream must have all bytes available
     * for the frame prior to making this call.
     *
     * @param in The input stream. Not null.
     * @return The {@link WindowUpdateFrame} instance.
     */
    public static WindowUpdateFrame parse(HttpInputStream in) {
        // SPEC:
        // A WINDOW_UPDATE frame with a length other than 4 octets MUST be treated as a connection error
        // (Section 5.4.1) of type FRAME_SIZE_ERROR.
        final var frameLength = in.read24BitInteger();
        if (frameLength != 4) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, readAheadStreamId(in));
        }

        // Read past the type
        final var type = in.readByte();
        assert type == FrameType.WINDOW_UPDATE.ordinal()
                : "Wrong method called, type mismatch " + type + " not for window update";

        // SPEC:
        // The WINDOW_UPDATE frame does not define any flags.
        in.skip(1);

        // Read the stream ID (can be any value)
        final var streamId = in.read31BitInteger();

        // SPEC:
        // A receiver MUST treat the receipt of a WINDOW_UPDATE frame with a flow-control window increment of 0 as a
        // stream error (Section 5.4.2) of type PROTOCOL_ERROR
        final var windowSizeIncrement = in.read31BitInteger();
        if (windowSizeIncrement == 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        return new WindowUpdateFrame(streamId, windowSizeIncrement);
    }
}
