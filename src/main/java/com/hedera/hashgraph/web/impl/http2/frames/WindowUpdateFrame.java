package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;
import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

/**
 * An implementation for WINDOW_UPDATE frame types.
 *
 * <p>SPEC: 6.9<br>
 * The WINDOW_UPDATE frame (type=0x08) is used to implement flow control; see Section 5.2 for an overview.
 *
 * <p>Flow control operates at two levels: on each individual stream and on the entire connection.
 *
 * <p>Flow control only applies to frames that are identified as being subject to flow control. Of the frame types
 * defined in this document, this includes only DATA frames. Frames that are exempt from flow control MUST be accepted
 * and processed, unless the receiver is unable to assign resources to handling the frame. A receiver MAY respond with
 * a stream error (Section 5.4.2) or connection error (Section 5.4.1) of type FLOW_CONTROL_ERROR if it is unable to
 * accept a frame.
 */
public final class WindowUpdateFrame extends Frame {

    /**
     * The window size increment value. Will never be 0.
     */
    private int windowSizeIncrement;

    /**
     * Create a new instance.
     */
    public WindowUpdateFrame() {
        super(FrameType.WINDOW_UPDATE);
        setPayloadLength(4);
    }

    /**
     * Create instance.
     *
     * @param streamId The stream ID, which must not be negative.
     * @param windowSizeIncrement The window size increment, which must be positive
     */
    public WindowUpdateFrame(int streamId, int windowSizeIncrement) {
        super(4, FrameType.WINDOW_UPDATE, (byte) 0, streamId);
        this.windowSizeIncrement = windowSizeIncrement;
        if (windowSizeIncrement < 1) {
            throw new IllegalArgumentException("The window size increment must be positive");
        }
    }

    /**
     * Sets the stream ID
     *
     * @param streamId the stream ID to set
     * @return a reference to this
     */
    @Override
    public WindowUpdateFrame setStreamId(int streamId) {
        return (WindowUpdateFrame) super.setStreamId(streamId);
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
     * Sets the window size increment
     * @param windowSizeIncrement The increment, must be non-negative.
     */
    public WindowUpdateFrame setWindowSizeIncrement(int windowSizeIncrement) {
        if (windowSizeIncrement < 0) {
            throw new IllegalArgumentException("Must be non-negative");
        }

        this.windowSizeIncrement = windowSizeIncrement;
        return this;
    }

    /**
     * Parses a WindowFrameUpdate from the input stream. The stream must have all bytes available
     * for the frame prior to making this call.
     *
     * @param in The input stream. Not null.
     */
    @Override
    public void parse2(InputBuffer in) {
        super.parse2(in);

        // Read the stream ID (can be any value)
        final var streamId = getStreamId();

        // SPEC:
        // A WINDOW_UPDATE frame with a length other than 4 octets MUST be treated as a connection error
        // (Section 5.4.1) of type FRAME_SIZE_ERROR.
        final var payloadLength = getPayloadLength();
        if (payloadLength != 4) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, streamId);
        }

        // SPEC:
        // A receiver MUST treat the receipt of a WINDOW_UPDATE frame with a flow-control window increment of 0 as a
        // stream error (Section 5.4.2) of type PROTOCOL_ERROR
        this.windowSizeIncrement = in.read31BitInteger();
        if (windowSizeIncrement == 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(final OutputBuffer out) {
        super.write(out);
        out.write32BitInteger(windowSizeIncrement);
    }
}
