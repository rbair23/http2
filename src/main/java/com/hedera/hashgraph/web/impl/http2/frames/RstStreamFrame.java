package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;
import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.util.Objects;

/**
 * An implementation of the RST_STREAM frame.
 *
 * <p>SPEC: 6.4<br>
 * The RST_STREAM frame (type=0x03) allows for immediate termination of a stream. RST_STREAM is sent to request
 * cancellation of a stream or to indicate that an error condition has occurred.
 */
public final class RstStreamFrame extends Frame {

    /**
     * The error code indicates why the stream is being closed. This will
     * never be null, and defaults to NO_ERROR.
     */
    private Http2ErrorCode errorCode = Http2ErrorCode.NO_ERROR;

    /**
     * Create a new instance.
     */
    public RstStreamFrame() {
        super(FrameType.RST_STREAM);
        setPayloadLength(4);
    }

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
     */
    @Override
    public void parse2(InputBuffer in) {
        super.parse2(in);

        // SPEC: 6.4. RST_STREAM
        // If a RST_STREAM frame is received with a stream identifier of 0x00, the recipient MUST treat this as a
        // connection error (Section 5.4.1) of type PROTOCOL_ERROR.
        final var streamId = getStreamId();
        if (streamId == 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        // SPEC:
        // A RST_STREAM frame with a length other than 4 octets MUST be treated as a connection error (Section 5.4.1)
        // of type FRAME_SIZE_ERROR.
        if (getFrameLength() != 4) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, streamId);
        }

        // SPEC: 6.4 RST_STREAM
        // RST_STREAM frame contains a single unsigned, 32-bit integer identifying the error code (Section 7). The
        // error code indicates why the stream is being terminated.
        this.errorCode = Http2ErrorCode.fromOrdinal(in.read32BitInteger());
    }

    /**
     * Write an RST_STREAM to the output.
     *
     * @param out The output stream. Cannot be null.
     */
    @Override
    public void write(OutputBuffer out) {
        // Write out the header.
        super.write(out);
        out.write32BitUnsignedInteger(errorCode.ordinal());
    }
}
