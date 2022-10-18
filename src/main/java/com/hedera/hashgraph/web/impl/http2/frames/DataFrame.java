package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;
import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.util.Objects;

/**
 * The frame for request and response data.
 *
 * <p>SPEC 6.1<br>
 * DATA frames (type=0x00) convey arbitrary, variable-length sequences of octets associated with a stream. One or more
 * DATA frames are used, for instance, to carry HTTP request or response message contents.
 *
 * <p>DATA frames MAY also contain padding. Padding can be added to DATA frames to obscure the size of messages.
 * Padding is a security feature; see Section 10.7.
 *
 * <p>(End Spec)</p>
 * <p>Note that padding is only meaningful as a security mechanism while the frame is on the wire. Since we don't need
 * the padding data sent from the client, we can just discard it when parsing. And when the server is creating data
 * frames and decides to pad, it can do so on the fly and doesn't need it held on the frame itself.
 *
 * <p>This means, that while the on-wire PADDED flag may be set, it is NOT set on the {@link DataFrame} after parsing.
 */
public final class DataFrame extends Frame {
    /**
     * The data for this frame. We provide direct access to this buffer to avoid buffer
     * copies, so take care with it. It will never be null, but may be larger than the
     * actual frame data (again, to avoid object churn we don't create new arrays
     * every time but use this as a buffer).
     */
    private byte[] data = new byte[1024];

    /**
     * The number of significant bytes in {@link #data}.
     */
    private int dataLength = 0;

    /**
     * Create a new Data Frame.
     */
    public DataFrame() {
        super(FrameType.DATA);
    }

    /**
     * Create a new Data Frame.
     *
     * @param endStream Whether this frame represents the end-of-stream.
     * @param streamId The stream ID. Must be positive.
     * @param data The block of data. This is taken as is, and not defensively copied! Not null.
     * @param dataLength The number of bytes in {@link #data} that hold meaningful data.
     */
    public DataFrame(boolean endStream, int streamId, byte[] data, int dataLength) {
        super(dataLength, FrameType.DATA, (byte) (endStream ? 1 : 0), streamId);
        this.data = Objects.requireNonNull(data);
        this.dataLength = dataLength;
        if (data.length < dataLength) {
            throw new IllegalArgumentException("The dataLength cannot exceed the data.length");
        }
        if (streamId < 1) {
            throw new IllegalArgumentException("The stream id cannot be < 1");
        }
    }

    /**
     * Gets the data buffer. This is NOT a defensive copy, so take care with it.
     *
     * @return The data. This will not be null.
     */
    public byte[] getData() {
        return data;
    }

    /**
     * Gets the number of meaningful bytes in {@link #getData()}.
     *
     * @return The number of meaningful bytes. Will not be negative.
     */
    public int getDataLength() {
        return dataLength;
    }

    /**
     * Gets whether this is the very last frame of the stream.
     *
     * @return {@code true} if this frame is the last frame in the stream
     */
    public boolean isEndStream() {
        return super.isEighthFlagSet();
    }

    /**
     * Specifies whether this frame is the last frame of the stream.
     *
     * @param endStream Whether this frame is the last frame of the stream.
     */
    public void setEndStream(boolean endStream) {
        super.setEighthFlag(endStream);
    }

    /**
     * Gets whether this frame is padded.
     *
     * @return true if padded
     */
    private boolean isPadded() {
        return super.isFifthFlagSet();
    }

    /**
     * Clears the padded flag
     */
    private void clearPadded() {
        super.setFifthFlag(false);
    }

    /**
     * Parses a {@link DataFrame} from the given input stream. The stream must have enough available bytes
     * to read an entire frame before calling this method.
     *
     * @param in The input stream. Cannot be null and must have the entire frame's data buffered up already.
     */
    @Override
    public void parse2(InputBuffer in) {
        // First, read off the header information. These are the first 9 bytes.
        super.parse2(in);

        // Discover whether the parsed header data included a padded flag, then clear it,
        // since we don't keep padding information in the frame instance
        final var paddedFlag = isPadded();
        clearPadded();

        // SPEC: 6.1
        // DATA frames MUST be associated with a stream. If a DATA frame is received whose Stream Identifier field is
        // 0x00, the recipient MUST respond with a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
        final var streamId = getStreamId();
        if (streamId == 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        // Note that the "END_STREAM" flag is semantically meaningful in the HTTP2 workflows,
        // but not during parsing. So we don't care what value is set.

        // Get the padLength, if present.
        final var padLength = paddedFlag ? in.readByte() : 0;

        // Compute the number of bytes that are part of the payload that are part of the block length,
        // and then read the block of fragment data.
        final var extraBytesRead = (paddedFlag ? 1 : 0);
        dataLength = getPayloadLength() - (padLength + extraBytesRead);
        setPayloadLength(dataLength);

        // Resize the data buffer if necessary. Other parts of the system validate that
        // we are not reading too much data, as per server configuration, so we don't
        // need to worry about this causing runaway buffer sizes. It could cause memory
        // churn in the short term as payload sizes stabilize. To mitigate this, we take
        // the blockDataLength and round it up to 1K block sizes, so at most we would get
        // maybe 6 resizes.
        if (this.data == null || this.data.length < dataLength) {
            final var arrayLength = roundUpToNearestOneK(dataLength);
            this.data = new byte[arrayLength];
        }

        // Read the data into the data buffer for this frame
        in.readBytes(this.data, 0, dataLength);

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
        out.write(data, 0, dataLength);
    }
}
