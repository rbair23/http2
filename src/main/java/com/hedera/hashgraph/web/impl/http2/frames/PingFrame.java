package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

/**
 * A connection-level frame.
 */
public final class PingFrame extends Frame {
    public static final int PAYLOAD_LENGTH = 8;

    /**
     * 8 bytes of random data, but we just interpret that as a number because it is more efficient
     */
    private long data;

    /**
     * Create a new PingFrame
     */
    public PingFrame() {
        super(FrameType.PING);
        setPayloadLength(PAYLOAD_LENGTH);
    }

    /**
     * Create a new PingFrame
     */
    public PingFrame(boolean ack, long data) {
        super(FrameType.PING);
        setPayloadLength(PAYLOAD_LENGTH);
        setAck(ack);
        setData(data);
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
     * Set the ack.
     *
     * @param ack the value
     */
    private void setAck(boolean ack) {
        super.setEighthFlag(ack);
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
     * Sets the data on this frame.
     *
     * @param data The data.
     */
    private void setData(long data) {
        this.data = data;
    }

    /**
     * Parses a {@link PingFrame} from the input stream. All bytes for the frame must be available
     * at the time this method is called.
     *
     * @param in The input stream. Cannot be null.
     */
    @Override
    public void parse2(InputBuffer in) {
        super.parse2(in);

        // The stream ID, which *MUST* be zero
        final var streamId = getStreamId();
        if (streamId != 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        // SPEC: 6.7 PING
        // Receipt of a PING frame with a length field value other than 8 MUST be treated as a connection error
        // (Section 5.4.1) of type FRAME_SIZE_ERROR.
        if (getPayloadLength() != PAYLOAD_LENGTH) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, streamId);
        }

        // Read off the data, which is some arbitrary 8 bytes. We can read that as a long,
        // so we do so, because it is cheap and easy to represent on the computer (but
        // it does take longer to decode, so I'm not totally sold on the approach).
        setData(in.read64BitLong());
    }

    @Override
    public void write(OutputBuffer out) {
        super.write(out);
        out.write64BitLong(data);
    }

    public static void writeAck(OutputBuffer outputBuffer, long pingData) {
        Frame.writeHeader(outputBuffer, PAYLOAD_LENGTH, FrameType.PING, (byte) 0x1, 0);
        outputBuffer.write64BitLong(pingData);
    }
}
