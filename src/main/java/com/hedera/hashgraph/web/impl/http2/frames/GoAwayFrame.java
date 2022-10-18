package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;
import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.IOException;

public final class GoAwayFrame extends Frame {
    private int lastStreamId = -1;
    private Http2ErrorCode errorCode = null;

    /**
     * Create a new instance.
     *
     * @param streamId The stream id must be non-negative.
     * @param lastStreamId The last stream ID that was successful
     * @param errorCode The error code associated with this frame
     */
    public GoAwayFrame(int streamId, int lastStreamId, Http2ErrorCode errorCode) {
        super(0, FrameType.GO_AWAY, (byte) 0, streamId);
        this.lastStreamId = lastStreamId;
        this.errorCode = errorCode;
    }

    /**
     * Write an RST_STREAM to the output.
     *
     * @param out The output stream. Cannot be null.
     * @param code The error code. Cannot be null.
     * @param streamId The stream id. Must not be 0.
     * @throws IOException An exception during writing
     */
    public static void write(OutputBuffer out, Http2ErrorCode code, int streamId, int lastStreamId) {
        // Write out the header.
        Frame.writeHeader(out, 8, FrameType.GO_AWAY, (byte) 0, streamId);
        out.write32BitInteger(lastStreamId);
        out.write32BitUnsignedInteger(code.ordinal());
    }

    public static GoAwayFrame parse(InputBuffer in) {
        // Read off the frame length. Validated later.
        final var frameLength = in.read24BitInteger();

        // Read past the type
        final var type = in.readByte();
        assert type == FrameType.GO_AWAY.ordinal()
                : "Wrong method called, type mismatch " + type + " not for go-away";

        // The flags are unused
        in.skip(1);

        // The stream ID, which *MUST* be zero
        final var streamId = in.read31BitInteger();
        if (streamId != 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        final int lastStreamId = in.read31BitInteger();
        final var errorCode = Http2ErrorCode.fromOrdinal(in.read32BitInteger());

        if (frameLength > 8) {
            in.skip(frameLength - 8);
        }

        return new GoAwayFrame(streamId, lastStreamId, errorCode);
    }

    public Http2ErrorCode getErrorCode() {
        return errorCode;
    }
}
