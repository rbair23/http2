package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.util.HttpInputStream;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;

import java.io.IOException;
import java.util.Objects;

public final class RstStreamFrame extends Frame {

    /**
     * The error code indicates why the stream is being closed
     */
    private final Http2ErrorCode errorCode;

    /**
     * Create a new instance.
     *
     * @param streamId The stream ID. Will not be 0.
     * @param errorCode The error code. Will not be null.
     */
    public RstStreamFrame(int streamId, Http2ErrorCode errorCode) {
        super(4, FrameType.RST_STREAM, (byte) 0, streamId);
        this.errorCode = Objects.requireNonNull(errorCode);
    }

    /**
     * Gets the error code.
     *
     * @return A non-null error code
     */
    public Http2ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * Parses an RST_FRAME from the input stream. The input stream must have the entire frames data
     * buffered and ready to be read.
     *
     * @param in The input stream, cannot be null
     * @return A new frame instance
     */
    public static RstStreamFrame parse(HttpInputStream in) {
        // SPEC:
        // A RST_STREAM frame with a length other than 4 octets MUST be treated as a connection error (Section 5.4.1)
        // of type FRAME_SIZE_ERROR.
        final var frameLength = in.read24BitInteger();
        if (frameLength != 4) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, readAheadStreamId(in));
        }

        // Read past the type
        final var type = in.readByte();
        assert type == FrameType.RST_STREAM.ordinal()
                : "Wrong method called, type mismatch " + type + " not for reset stream";

        // SPEC:
        // The RST_STREAM frame does not define any flags.
        in.skip(1);

        // SPEC:
        // If a RST_STREAM frame is received with a stream identifier of 0x00, the recipient MUST treat this as a
        // connection error (Section 5.4.1) of type PROTOCOL_ERROR.
        final var streamId = in.read31BitInteger();
        if (streamId == 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        // SPEC:
        // RST_STREAM frame contains a single unsigned, 32-bit integer identifying the error code (Section 7). The
        // error code indicates why the stream is being terminated.
        final var errorCode = Http2ErrorCode.fromOrdinal(in.read32BitInteger());
        return new RstStreamFrame(streamId, errorCode);
    }

    /**
     * Write an RST_STREAM to the output.
     *
     * @param out The output stream. Cannot be null.
     * @param code The error code. Cannot be null.
     * @param streamId The stream id. Must not be 0.
     * @throws IOException An exception during writing
     */
    public static void write(OutputBuffer out, Http2ErrorCode code, int streamId) throws IOException {
        // Write out the header.
        Frame.writeHeader(out, 4, FrameType.RST_STREAM, (byte) 0, streamId);
        out.write32BitUnsignedInteger(code.ordinal());
    }
}
