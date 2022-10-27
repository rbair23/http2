package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

/**
 * A connection-level frame, the {@link PingFrame} simply carries 8 bytes of payload and
 * an "ACK" flag.
 *
 * <p>SPEC: 6.7<br>
 * The PING frame (type=0x06) is a mechanism for measuring a minimal round-trip time from the sender, as well as
 * determining whether an idle connection is still functional. PING frames can be sent from any endpoint,
 */
public final class PingFrame extends Frame {
    /**
     * The only valid length for the payload of a PING frame is 8.
     */
    public static final int PAYLOAD_LENGTH = 8;

    /**
     * 8 bytes of random data, but we just interpret that as a number because it is more efficient
     */
    private byte[] data = new byte[8];

    /**
     * Create a new PingFrame
     */
    public PingFrame() {
        super(FrameType.PING);
        setPayloadLength(PAYLOAD_LENGTH);
    }

    /**
     * Create a new PingFrame
     *
     * @param ack The value to set for the "ack" flag
     * @param data The value to use for the data
     */
    public PingFrame(boolean ack, byte[] data) {
        super(FrameType.PING);
        setPayloadLength(PAYLOAD_LENGTH);
        setAck(ack);
        setData(data);
    }

    /**
     * Gets whether this is an ACK ping frame.
     *
     * <p>SPEC: 6.7<br>
     * When set, the ACK flag indicates that this PING frame is a PING response. An endpoint MUST set this flag in PING
     * responses. An endpoint MUST NOT respond to PING frames containing this flag.
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
     * Gets the data.
     *
     * <p>SPEC: 6.7<br>
     * In addition to the frame header, PING frames MUST contain 8 octets of opaque data in the frame payload. A
     * sender can include any value it chooses and use those octets in any fashion.
     *
     * @return the 8 bytes of data
     */
    public byte[] getData() {
        return data;
    }

    /**
     * Sets the data on this frame.
     *
     * @param data The data.
     */
    private void setData(byte[] data) {
        this.data = data;
    }

    /**
     * Parses {@link PingFrame} values from the input stream. All bytes for the frame must be available
     * at the time this method is called.
     *
     * @param in The input stream. Cannot be null.
     */
    @Override
    public void parse2(final InputBuffer in) {
        super.parse2(in);

        // SPEC: 6.7
        // PING frames are not associated with any individual stream. If a PING frame is received with a Stream
        // Identifier field value other than 0x00, the recipient MUST respond with a connection error (Section 5.4.1)
        // of type PROTOCOL_ERROR.
        final var streamId = getStreamId();
        if (streamId != 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        // SPEC: 6.7
        // Receipt of a PING frame with a length field value other than 8 MUST be treated as a connection error
        // (Section 5.4.1) of type FRAME_SIZE_ERROR.
        if (getPayloadLength() != PAYLOAD_LENGTH) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, streamId);
        }

        // SPEC: 6.7
        // In addition to the frame header, PING frames MUST contain 8 octets of opaque data in the frame payload. A
        // sender can include any value it chooses and use those octets in any fashion.
        in.readBytes(data, 0, 8);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(final OutputBuffer out) {
        super.write(out);
        out.write(data);
    }

    /**
     * Writes an "ACK" PING frame to the output buffer with the same data as this ping frame.
     *
     * @param outputBuffer The buffer to write to. Must not be null and must have space.
     */
    public void writeAck(final OutputBuffer outputBuffer) {
        Frame.writeHeader(outputBuffer, PAYLOAD_LENGTH, FrameType.PING, (byte) 0x1, 0);
        outputBuffer.write(data);
    }
}
