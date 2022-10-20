package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;
import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

/**
 * Represents a CONTINUATION HTTP/2 frame.
 *
 * <p>SPEC 6.10<br>
 * The CONTINUATION frame (type=0x09) is used to continue a sequence of field block fragments (Section 4.3). Any number
 * of CONTINUATION frames can be sent, as long as the preceding frame is on the same stream and is a HEADERS,
 * PUSH_PROMISE, or CONTINUATION frame without the END_HEADERS flag set.
 *
 * <p>(End Spec)</p>
 * <p>Note that our server never sends a PUSH_PROMISE or receives a PUSH_PROMISE, so we can ignore that case.
 * For this implementation, we therefore can say that a {@link ContinuationFrame} always follows a {@link HeadersFrame}.
 */
public final class ContinuationFrame extends HeadersFrameBase {

    /**
     * Create a new Continuation Frame.
     */
    public ContinuationFrame() {
        super(FrameType.CONTINUATION);
    }

    /**
     * Create a new Continuation Frame.
     *
     * @param endHeaders Whether this is the end of the headers
     * @param streamId The stream ID. Must be positive.
     * @param fieldBlockFragment The block of data. This is taken as is, and not defensively copied! Not null.
     * @param blockLength The number of bytes in {@code fieldBlockFragment} that hold meaningful data.
     */
    public ContinuationFrame(boolean endHeaders, int streamId, byte[] fieldBlockFragment, int blockLength) {
        super(FrameType.CONTINUATION, endHeaders, streamId, fieldBlockFragment, blockLength);
    }

    /**
     * Sets the stream ID
     *
     * @param streamId the stream ID to set
     * @return a reference to this
     */
    @Override
    public ContinuationFrame setStreamId(int streamId) {
        assert streamId != 0;
        return (ContinuationFrame) super.setStreamId(streamId);
    }

    /**
     * Parses a {@link ContinuationFrame} from the given input stream. The stream must have enough available bytes
     * to read an entire frame before calling this method.
     *
     * @param in The input stream. Cannot be null and must have the entire frame's data buffered up already.
     */
    @Override
    public void parse2(InputBuffer in) {
        // First, read off the header information. These are the first 9 bytes.
        super.parse2(in);

        // SPEC: 6.10
        // CONTINUATION frames MUST be associated with a stream. If a CONTINUATION frame is received with a Stream
        // Identifier field of 0x00, the recipient MUST respond with a connection error (Section 5.4.1) of type
        // PROTOCOL_ERROR.
        final var streamId = getStreamId();
        if (streamId == 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        // Note that the "END_STREAM" flag is semantically meaningful in the HTTP2 workflows,
        // but not during parsing. So we don't care what value is set.
        readFieldBlockData(getPayloadLength(), in);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(OutputBuffer out) {
        assert getStreamId() != 0 : "Failed to update the stream ID prior to writing";
        super.write(out);
        out.write(getFieldBlockFragment(), 0, getBlockLength());
    }
}
