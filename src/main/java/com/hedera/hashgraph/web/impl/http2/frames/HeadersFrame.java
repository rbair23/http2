package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.impl.HttpInputStream;
import com.hedera.hashgraph.web.impl.HttpOutputStream;
import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;

import java.io.IOException;
import java.util.Objects;

/**
 * The frame for carrying request and response headers.
 */
public final class HeadersFrame extends Frame {
    // Constants for the different flags used by this frame
    private static final int PRIORITY_FLAG = THIRD_FLAG;
    private static final int PADDED_FLAG = FIFTH_FLAG;

    /**
     * The compressed header data. This may be the complete content if it fits into the maximum frame size,
     * or it may be just a fragment if spread across a HeadersFrame and one or more ContinuationFrames.
     */
    private byte[] fieldBlockFragment;

    /**
     * Create a new Headers Frame.
     *
     * @param length The length of this frame. Actually, this can be computed based on some other data...
     * @param flags The flags
     * @param streamId The stream ID
     * @param fieldBlockFragment The block of data. This is taken as is, and not defensively copied!
     */
    public HeadersFrame(int length, byte flags, int streamId, byte[] fieldBlockFragment) {
        super(length, FrameType.HEADERS, flags, streamId);
        this.fieldBlockFragment = Objects.requireNonNull(fieldBlockFragment);
    }

    public byte[] getFieldBlockFragment() {
        return fieldBlockFragment;
    }

    /**
     * Gets whether this is a complete header (i.e. there are no following continuation frames).
     *
     * @return {@code true} if this header is complete and there are no following continuation frames.
     */
    public boolean isCompleteHeader() {
        return super.isSixthFlagSet();
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
     * Parses a {@link HeadersFrame} from the given input stream. The stream must have enough available bytes
     * to read an entire frame before calling this method.
     *
     * @param in The input stream. Cannot be null and must have the entire frame's data buffered up already.
     * @return A non-null {@link HeadersFrame}
     */
    public static HeadersFrame parse(HttpInputStream in) {
        // First, read off the header information. These are the first 9 bytes.

        // Read the length off. We will use this to compute the length of fragment data
        final var frameLength = in.read24BitInteger();
        // Read past the type. We don't actually need this, but for safety we can assert here
        final var type = in.readByte();
        assert type == FrameType.HEADERS.ordinal() : "Unexpected frame type, was not HEADERS";
        // Get the flags, and interpret a couple of them (since we need to know them when processing
        // the payload body)
        var flags = in.readByte();
        final var priorityFlag = (flags & PRIORITY_FLAG) != 0;
        final var paddedFlag = (flags & PADDED_FLAG) != 0;
        // Get the stream id
        final var streamId = in.read31BitInteger();

        // The header section (first 9 bytes) have been read. Now we need to read the body. There are
        // exactly `frameLength` bytes in the body. Some of these may be devoted to state depending
        // on the `priority` and `padded` flags. The rest will be header fragment data (compressed
        // headers).

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
        final var blockDataLength = frameLength - (padLength + extraBytesRead);
        final byte[] fieldBlockFragment = new byte[blockDataLength];
        in.readBytes(fieldBlockFragment, 0, blockDataLength);

        // Skip any padding that may have been there.
        in.skip(padLength);

        // TODO When I read the fieldBlockFragment, I don't actually want to read it into a temporary byte array,
        //      I want to actually read it into the destination array. So this is wrong.

        return new HeadersFrame(frameLength, flags, streamId, fieldBlockFragment);
    }

    public static void write(HttpOutputStream out, int streamId, byte[] data) throws IOException {
        Frame.writeHeader(out, data.length, FrameType.HEADERS, (byte) 0x0, streamId);
        out.writeBytes(data, 0, data.length);
    }
}
