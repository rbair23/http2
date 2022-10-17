package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;
import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.IOException;

public final class PriorityFrame extends Frame {
    /**
     * The only possible length for the payload of the PRIORITY frame
     */
    private static final int PAYLOAD_LENGTH = 5;

    /**
     * Create a new instance.
     *
     * @param streamId      The stream id must be non-negative.
     */
    public PriorityFrame(int streamId) {
        super(PAYLOAD_LENGTH, FrameType.PRIORITY, (byte) 0, streamId);
    }

    public static PriorityFrame parse(InputBuffer in) {
        // Read the length (check it later)
        final var frameLength = in.read24BitInteger();

        // Read past the type. We don't actually need this, but for safety we can assert here
        final var type = in.readByte();
        assert type == FrameType.HEADERS.ordinal() : "Unexpected frame type, was not HEADERS";

        // SPEC: 6.3 PRIORITY
        // The PRIORITY frame does not define any flags.
        in.skip(1);

        // SPEC: 6.3 PRIORITY
        // The PRIORITY frame always identifies a stream. If a PRIORITY frame is received with a stream identifier of
        // 0x00, the recipient MUST respond with a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
        final var streamId = in.read31BitInteger();
        if (streamId == 0) {
            throw  new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        // SPEC: 6.3 PRIORITY
        // A PRIORITY frame with a length other than 5 octets MUST be treated as a stream error (Section 5.4.2) of
        // type FRAME_SIZE_ERROR.
        if (frameLength != PAYLOAD_LENGTH) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, streamId);
        }

        // Skip the payload data. We don't need it.
        in.skip(PAYLOAD_LENGTH);

        return new PriorityFrame(streamId);
    }
}
