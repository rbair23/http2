package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.IOException;
import java.util.Objects;

/**
 * The frame for request and response data.
 */
public final class DataFrame extends Frame {
    private static final int PADDED_FLAG = FIFTH_FLAG;

    /**
     * The data. This may be the complete content if it fits into the maximum frame size,
     * or it may be just a portion of the data if spread across a DataFrame and one or more ContinuationFrames.
     */
    private byte[] data;

    /**
     * Create a new Data Frame.
     *
     * @param length The length of this frame. Actually, this can be computed based on some other data...
     * @param flags The flags
     * @param streamId The stream ID
     * @param data The block of data. This is taken as is, and not defensively copied!
     */
    public DataFrame(int length, byte flags, int streamId, byte[] data) {
        super(length, FrameType.DATA, flags, streamId);
        this.data = Objects.requireNonNull(data);
    }

    public byte[] getData() {
        return data;
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
     * Parses a {@link DataFrame} from the given input stream. The stream must have enough available bytes
     * to read an entire frame before calling this method.
     *
     * @param in The input stream. Cannot be null and must have the entire frame's data buffered up already.
     * @return A non-null {@link DataFrame}
     */
    public static DataFrame parse(InputBuffer in) {
        // First, read off the header information. These are the first 9 bytes.

        // Read the length off. We will use this to compute the length of fragment data
        final var frameLength = in.read24BitInteger();
        // Read past the type. We don't actually need this, but for safety we can assert here
        final var type = in.readByte();
        assert type == FrameType.DATA.ordinal() : "Unexpected frame type, was not DATA";
        // Get the flags, and interpret a couple of them (since we need to know them when processing
        // the payload body)
        var flags = in.readByte();
        final var paddedFlag = (flags & PADDED_FLAG) != 0;
        // Get the stream id
        final var streamId = in.read31BitInteger();

        // The header section (first 9 bytes) have been read. Now we need to read the body. There are
        // exactly `frameLength` bytes in the body. Some of these may be devoted to state depending
        // on the `padded` flag. The rest will be actual data.

        // Get the padLength, if present.
        final var padLength = paddedFlag ? in.readByte() : 0;

        // Compute the number of bytes that are part of the payload that are part of the block length,
        // and then read the block of fragment data.
        final var extraBytesRead = (paddedFlag ? 1 : 0);
        final var blockDataLength = frameLength - (padLength + extraBytesRead);
        final byte[] data = new byte[blockDataLength];
        in.readBytes(data, 0, blockDataLength);

        // Skip any padding that may have been there.
        in.skip(padLength);

        // TODO When I read the data, I don't actually want to read it into a temporary byte array,
        //      I want to actually read it into the destination array. So this is wrong.

        return new DataFrame(frameLength, flags, streamId, data);
    }

    public static void writeHeader(OutputBuffer out, int streamId, int dataSize) throws IOException {
        Frame.writeHeader(out, dataSize, FrameType.DATA, (byte) 0x0, streamId);
    }
    public static void writeLastHeader(OutputBuffer out, int streamId) throws IOException {
        Frame.writeHeader(out, 0, FrameType.DATA, (byte) 0x1, streamId);
    }

    public static void write(OutputBuffer out, int streamId, boolean last, OutputBuffer frameData) throws IOException {
        Frame.writeHeader(out, frameData.size(), FrameType.DATA, (byte) 0x0, streamId);
        out.write(frameData);

        if (last) {
            Frame.writeHeader(out, 0, FrameType.DATA, (byte) 0x1, streamId);
        }
    }

}
