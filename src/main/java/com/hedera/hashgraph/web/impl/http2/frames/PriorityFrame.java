package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;
import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.IOException;

/**
 * Represents a PRIORITY frame. This frame type is not supported by this server, but we still
 * need to be able to parse it. And we never write it.
 *
 * <p>SPEC 6.1<br>
 * The PRIORITY frame (type=0x02) is deprecated; see Section 5.3.2. A PRIORITY frame can be sent in any stream state,
 * including idle or closed streams.
 *
 * <p>(End Spec)</p>
 */
public final class PriorityFrame extends Frame {
    /**
     * The only possible length for the payload of the PRIORITY frame
     */
    private static final int PAYLOAD_LENGTH = 5;

    /**
     * Create a new instance.
     */
    public PriorityFrame() {
        super(FrameType.PRIORITY);
        setPayloadLength(PAYLOAD_LENGTH);
    }

    /**
     * Create a new instance.
     *
     * @param streamId      The stream id must be non-negative.
     */
    public PriorityFrame(int streamId) {
        super(PAYLOAD_LENGTH, FrameType.PRIORITY, (byte) 0, streamId);
    }

    /**
     * Sets the stream ID
     *
     * @param streamId the stream ID to set
     * @return a reference to this
     */
    @Override
    public PriorityFrame setStreamId(int streamId) {
        assert streamId != 0;
        return (PriorityFrame) super.setStreamId(streamId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void parse2(InputBuffer in) {
        super.parse2(in);

        // SPEC: 6.3 PRIORITY
        // The PRIORITY frame always identifies a stream. If a PRIORITY frame is received with a stream identifier of
        // 0x00, the recipient MUST respond with a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
        final var streamId = getStreamId();
        if (streamId == 0) {
            throw  new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        // SPEC: 6.3 PRIORITY
        // A PRIORITY frame with a length other than 5 octets MUST be treated as a stream error (Section 5.4.2) of
        // type FRAME_SIZE_ERROR.
        if (getPayloadLength() != PAYLOAD_LENGTH) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, streamId);
        }

        // Skip the payload data. We don't need it.
        in.skip(PAYLOAD_LENGTH);
    }
}
