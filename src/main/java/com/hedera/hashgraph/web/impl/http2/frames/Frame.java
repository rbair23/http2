package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.IOException;

/**
 * A Frame represents a segment of data corresponding to a single "Stream". In HTTP/2, a single connection
 * sends multiple frames, interleaved. Each frame has a "streamId". The "0" stream is the special stream
 * representing the connection itself. All other IDs represent some other specific stream. Each Frame has
 * exactly a 9-byte header followed by an arbitrary number of bytes, up to some configured maximmum frame
 * size.
 */
public abstract class Frame {
    /**
     * Every frame in HTTP/2.0 is the same size -- 9 bytes.
     */
    public static final int FRAME_HEADER_SIZE = 9;
    protected static final byte FIRST_FLAG = (byte) 0b1000_0000;
    protected static final byte SECOND_FLAG = (byte) 0b0100_0000;
    protected static final byte THIRD_FLAG = (byte) 0b0010_0000;
    protected static final byte FOURTH_FLAG = (byte) 0b0001_0000;
    protected static final byte FIFTH_FLAG = (byte) 0b0000_1000;
    protected static final byte SIXTH_FLAG = (byte) 0b0000_0100;
    protected static final byte SEVENTH_FLAG = (byte) 0b0000_0010;
    protected static final byte EIGHTH_FLAG = (byte) 0b0000_0001;

    /**
     * This length makes up the first 3 bytes (24-bit unsigned int) of the frame and indicates the
     * length of the <b>payload</b> of the frame (everything not in the 9-byte header).
     */
    private final int payloadLength;

    /**
     * The type of this frame. On wire this is a single byte.
     */
    private final FrameType type;

    /**
     * A set of 8 flags, many of which are unused by specific frame types. The meaning of these flags
     * is frame-type specific.
     */
    private final byte flags;

    /**
     * The stream ID is a 31-bit unsigned integer, proceeded by a single reserved bit. This is
     * convenient as it means we can represent the stream ID as an integer and know it will
     * never be negative (since we can mask off the high-order bit).
     */
    private final int streamId;

    /**
     * Create a new instance. Only subclasses can call this constructor.
     *
     * @param payloadLength The length of the payload for this frame. Must be non-negative.
     * @param type The type of frame. This is specified by the subclass and must not be null and match the
     *             actual subclass type.
     * @param flags The set of flags.
     * @param streamId The stream id must be non-negative.
     */
    protected Frame(int payloadLength, FrameType type, byte flags, int streamId) {
        assert streamId >= 0 : "FrameID must not use the 32nd bit";
        assert payloadLength >= 0 : "The payload payloadLength must always non-negative";
        assert type != null : "You must specify the type (and please, make it accurate)";

        this.payloadLength = payloadLength;
        this.type = type;
        this.streamId = streamId;
        this.flags = flags;
    }

    /**
     * Gets the total length of the entire frame, including the header section and the payload section,
     * in bytes.
     *
     * @return The total length of the frame in bytes. This will always be a value 9 or greater.
     */
    public final int getFrameLength() {
        return 9 + getPayloadLength();
    }

    /**
     * Gets the length of the payload section of this frame, in bytes.
     *
     * @return The length of the payload section. This is always non-negative.
     */
    public final int getPayloadLength() {
        return payloadLength;
    }

    /**
     * Gets the stream ID.
     *
     * @return the stream id, which is always non-negative.
     */
    public final int getStreamId() {
        return streamId;
    }

    /**
     * Gets the frame type.
     *
     * @return the frame type enum, which is never null.
     */
    public FrameType getType() {
        return type;
    }

    /**
     * Gets whether the first flag has been set. This is a convenience for sub classes, which should
     * expose the name of the specific flag, calling this method.
     *
     * @return Whether the first flag is set.
     */
    protected final boolean isFirstFlagSet() {
        return (flags & FIRST_FLAG) != 0;
    }

    /**
     * Gets whether the second flag has been set. This is a convenience for sub classes, which should
     * expose the name of the specific flag, calling this method.
     *
     * @return Whether the second flag is set.
     */
    protected final boolean isSecondFlagSet() {
        return (flags & SECOND_FLAG) != 0;
    }

    /**
     * Gets whether the third flag has been set. This is a convenience for sub classes, which should
     * expose the name of the specific flag, calling this method.
     *
     * @return Whether the third flag is set.
     */
    protected final boolean isThirdFlagSet() {
        return (flags & THIRD_FLAG) != 0;
    }

    /**
     * Gets whether the fourth flag has been set. This is a convenience for sub classes, which should
     * expose the name of the specific flag, calling this method.
     *
     * @return Whether the fourth flag is set.
     */
    protected final boolean isFourthFlagSet() {
        return (flags & FOURTH_FLAG) != 0;
    }

    /**
     * Gets whether the fifth flag has been set. This is a convenience for sub classes, which should
     * expose the name of the specific flag, calling this method.
     *
     * @return Whether the fifth flag is set.
     */
    protected final boolean isFifthFlagSet() {
        return (flags & FIFTH_FLAG) != 0;
    }

    /**
     * Gets whether the sixth flag has been set. This is a convenience for sub classes, which should
     * expose the name of the specific flag, calling this method.
     *
     * @return Whether the sixth flag is set.
     */
    protected final boolean isSixthFlagSet() {
        return (flags & SIXTH_FLAG) != 0;
    }

    /**
     * Gets whether the seventh flag has been set. This is a convenience for sub classes, which should
     * expose the name of the specific flag, calling this method.
     *
     * @return Whether the seventh flag is set.
     */
    protected final boolean isSeventhFlagSet() {
        return (flags & SEVENTH_FLAG) != 0;
    }

    /**
     * Gets whether the eighth flag has been set. This is a convenience for sub classes, which should
     * expose the name of the specific flag, calling this method.
     *
     * @return Whether the eighth flag is set.
     */
    protected final boolean isEighthFlagSet() {
        return (flags & EIGHTH_FLAG) != 0;
    }

    /**
     * Writes the frame header to the output stream.
     *
     * @param out The output stream, which must not be null
     * @throws IOException If there is a problem writing to the stream
     */
    protected void writeHeader(OutputBuffer out) throws IOException {
        writeHeader(out, payloadLength, type, flags, streamId);
    }

    /**
     * Writes the frame header to the output stream.
     *
     * @param out The output stream, which must not be null
     * @param payloadLength The payload length
     * @param type The frame type
     * @param flags The flags
     * @param streamId The stream id
     */
    public static void writeHeader(final OutputBuffer out, int payloadLength, FrameType type, byte flags, int streamId) {
        out.write24BitInteger(payloadLength);
        out.writeByte(type.ordinal());
        out.writeByte(flags);
        out.write32BitInteger(streamId);
    }

    public static int readAheadStreamId(final InputBuffer in) {
        in.mark();
        in.skip(5);
        final var id = in.read31BitInteger();
        in.resetToMark();
        return id;
    }
}
