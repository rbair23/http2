package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.util.InputBuffer;

import java.util.Objects;

/**
 * A base class for HEADERS and CONTINUATION frames.
 */
public abstract class HeadersFrameBase extends Frame {
    /**
     * The data for this frame. We provide direct access to this buffer to avoid buffer
     * copies, so take care with it. It will never be null, but may be larger than the
     * actual frame data (again, to avoid object churn we don't create new arrays
     * every time but use this as a buffer).
     */
    private byte[] fieldBlockFragment = new byte[1024];

    /**
     * The number of significant bytes in {@link #fieldBlockFragment}.
     */
    private int blockLength = 0;

    /**
     * Create a new Header or Continuation Frame.
     *
     * @param frameType The type of frame. not null.
     */
    protected HeadersFrameBase(FrameType frameType) {
        super(frameType);
        assert frameType == FrameType.HEADERS || frameType == FrameType.CONTINUATION;
    }

    /**
     * Create a new Header or Continuation Frame.
     *
     * @param frameType The type of frame. not null.
     * @param endHeaders Whether this is the end of the headers
     * @param streamId The stream ID. Must be positive.
     * @param fieldBlockFragment The block of data. This is taken as is, and not defensively copied! Not null.
     * @param blockLength The number of bytes in {@link #fieldBlockFragment} that hold meaningful data.
     */
    protected HeadersFrameBase(
            FrameType frameType,
            boolean endHeaders,
            int streamId,
            byte[] fieldBlockFragment,
            int blockLength) {
        super(blockLength, frameType, (byte) 0, streamId);
        if (fieldBlockFragment.length < blockLength) {
            throw new IllegalArgumentException("The blockLength cannot exceed the fieldBlockFragment.length");
        }
        if (streamId < 1) {
            throw new IllegalArgumentException("The stream id cannot be < 1");
        }

        this.fieldBlockFragment = Objects.requireNonNull(fieldBlockFragment);
        this.blockLength = blockLength;
        setEndHeaders(endHeaders);
    }

    /**
     * Gets the fieldBlockFragment buffer. This is NOT a defensive copy, so take care with it.
     *
     * @return The fieldBlockFragment. This will not be null.
     */
    public final byte[] getFieldBlockFragment() {
        return fieldBlockFragment;
    }

    /**
     * Gets the number of meaningful bytes in {@link #getFieldBlockFragment()}.
     *
     * @return The number of meaningful bytes. Will not be negative.
     */
    public final int getBlockLength() {
        return blockLength;
    }

    public HeadersFrameBase setBlockLength(int blockLength) {
        this.blockLength = blockLength;
        super.setPayloadLength(blockLength);
        return this;
    }

    /**
     * Gets whether this is the very last frame of the headers.
     *
     * @return {@code true} if this frame is the last frame in the headers
     */
    public final boolean isEndHeaders() {
        return super.isSixthFlagSet();
    }

    /**
     * Specifies whether this frame is the last frame of the headers.
     *
     * @param endHeaders Whether this frame is the last frame of the headers.
     */
    public HeadersFrameBase setEndHeaders(boolean endHeaders) {
        super.setSixthFlag(endHeaders);
        return this;
    }

    /**
     * Given an {@link InputBuffer} positioned on the field block data, parse the field
     * block data.
     *
     * @param fieldBlockLength The number of bytes to read
     * @param in The input buffer which cannot be null.
     */
    protected void readFieldBlockData(int fieldBlockLength, InputBuffer in) {
        // Resize the data buffer if necessary. Other parts of the system validate that
        // we are not reading too much data, as per server configuration, so we don't
        // need to worry about this causing runaway buffer sizes. It could cause memory
        // churn in the short term as payload sizes stabilize. To mitigate this, we take
        // the blockDataLength and round it up to 1K block sizes, so at most we would get
        // maybe 6 resizes.
        this.blockLength = fieldBlockLength;
        assert fieldBlockLength >= 0;
        if (this.fieldBlockFragment == null || this.fieldBlockFragment.length < blockLength) {
            final var arrayLength = roundUpToNearestOneK(blockLength);
            this.fieldBlockFragment = new byte[arrayLength];
        }

        // Read the data into the data buffer for this frame
        in.readBytes(this.fieldBlockFragment, 0, blockLength);
    }
}
