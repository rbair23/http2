package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;
import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * The frame for carrying request and response headers.
 */
public final class HeadersFrame extends HeadersFrameBase {

    /**
     * Create a new Headers Frame.
     */
    public HeadersFrame() {
        super(FrameType.HEADERS);
    }

    /**
     * Create a new Headers Frame.
     *
     * @param endHeaders Whether this is the end of the headers
     * @param endStream Whether this frame represents the end-of-stream.
     * @param streamId The stream ID. Must be positive.
     * @param fieldBlockFragment The block of data. This is taken as is, and not defensively copied! Not null.
     * @param blockLength The number of bytes in {@code fieldBlockFragment} that hold meaningful data.
     */
    public HeadersFrame(boolean endHeaders, boolean endStream, int streamId, byte[] fieldBlockFragment, int blockLength) {
        super(FrameType.HEADERS, endHeaders, streamId, fieldBlockFragment, blockLength);
        setEndStream(endStream);
    }

    /**
     * Sets the stream ID
     *
     * @param streamId the stream ID to set
     * @return a reference to this
     */
    @Override
    public HeadersFrame setStreamId(int streamId) {
        assert streamId != 0;
        return (HeadersFrame) super.setStreamId(streamId);
    }

    /**
     * Gets whether the priority flag is set.
     * @return True if the priority flag is set
     */
    public boolean isPriority() {
        return isThirdFlagSet();
    }

    /**
     * Gets whether the padded flag is set.
     * @return True if the padded flag is set
     */
    public boolean isPadded() {
        return isFifthFlagSet();
    }

    /**
     * Gets whether this is the very last frame of the stream. For many GET requests, the headers frame
     * will be the very last of the stream.
     *
     * @return {@code true} if this header is the last frame in the stream
     */
    public boolean isEndStream() {
        return super.isEighthFlagSet();
    }

    /**
     * Sets the end stream flag to {@code value}.
     *
     * @param value The value for the end stream flag.
     */
    public HeadersFrame setEndStream(boolean value) {
        super.setEighthFlag(value);
        return this;
    }

    @Override
    public HeadersFrame setEndHeaders(boolean endHeaders) {
        super.setEndHeaders(endHeaders);
        return this;
    }

    @Override
    public HeadersFrame setBlockLength(int blockLength) {
        super.setBlockLength(blockLength);
        return this;
    }

    /**
     * Parses a {@link HeadersFrame} from the given input stream. The stream must have enough available bytes
     * to read an entire frame before calling this method.
     *
     * @param in The input stream. Cannot be null and must have the entire frame's data buffered up already.
     */
    @Override
    public void parse2(InputBuffer in) {
        // First, read off the header information. These are the first 9 bytes.
        super.parse2(in);

        // SPEC: 6.2
        // HEADERS frames MUST be associated with a stream. If a HEADERS frame is received whose Stream Identifier
        // field is 0x00, the recipient MUST respond with a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
        final var streamId = getStreamId();
        if (streamId == 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        // Get the flags, and interpret a couple of them (since we need to know them when processing
        // the payload body)
        final var priorityFlag = isPriority();
        final var paddedFlag = isPadded();

        // Get the padLength, if present.
        final var padLength = paddedFlag ? in.readByte() : 0;

        // If the priority flag is set, then the "exclusive", "streamDependency", and "weight"
        // fields will be present. The "exclusive" and "streamDependency" fields are part of a single
        // 32-bit integer, while the weight is a byte. But these fields are deprecated, and we don't
        // care about them, so we will just skip those 5 bytes.
        if (priorityFlag) {
            in.skip(5);
        }

        // Compute the number of bytes that are part of the payload that are part of the block length,
        // and then read the block of fragment data.
        final var extraBytesRead = (paddedFlag ? 1 : 0) + (priorityFlag ? 5 : 0);
        final var blockDataLength = getPayloadLength() - (padLength + extraBytesRead);
        readFieldBlockData(blockDataLength, in);

        // Skip any padding that may have been there.
        in.skip(padLength);
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
