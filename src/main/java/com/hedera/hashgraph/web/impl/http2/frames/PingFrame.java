package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.util.HttpInputStream;
import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.IOException;

/**
 * A connection-level frame.
 */
public final class PingFrame extends Frame {

    /**
     * 8 bytes of random data, but we just interpret that as a number because it is more efficient
     */
    private final long data;

    /**
     * Create a new PingFrame
     *
     * @param flags Any flags.
     * @param data The 8 bytes of data
     */
    public PingFrame(byte flags, long data) {
        super(8, FrameType.PING, flags, 0);
        this.data = data;
    }

    /**
     * Gets whether this is an ACK ping frame.
     *
     * @return Whether this is an ACK ping frame.
     */
    public boolean isAck() {
        return isEighthFlagSet();
    }

    /**
     * Gets the data
     *
     * @return the 8 bytes of data
     */
    public long getData() {
        return data;
    }

    /**
     * Parses a {@link PingFrame} from the input stream. All bytes for the frame must be available
     * at the time this method is called.
     *
     * @param in The input stream. Cannot be null.
     * @return The ping frame's data
     */
    public static long parseData(HttpInputStream in) {
        // Read off the frame length. This *MUST* be 8 bytes, exactly.
        final var frameLength = in.read24BitInteger();
        if (frameLength != 8) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, readAheadStreamId(in));
        }

        // Read past the type
        final var type = in.readByte();
        assert type == FrameType.PING.ordinal()
                : "Wrong method called, type mismatch " + type + " not for ping";

        // The flags
        var flags = in.readByte();

        // The stream ID, which *MUST* be zero
        final var streamId = in.read31BitInteger();
        if (streamId != 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        // Read off the data, which is some arbitrary 8 bytes. We can read that as a long,
        // so we do so, because it is cheap and easy to represent on the computer (but
        // it does take longer to decode, so I'm not totally sold on the approach).
        return in.read64BitLong();
    }

    public static void writeAck(OutputBuffer outputBuffer, long pingData) {
        Frame.writeHeader(outputBuffer, 8, FrameType.PING, (byte) 0x1, 0);
        outputBuffer.write64BitLong(pingData);
    }

}
