package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;
import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.util.Objects;

/**
 * Represents a GOAWAY HTTP/2 frame.
 *
 * <p>SPEC 6.8<br>
 * The GOAWAY frame (type=0x07) is used to initiate shutdown of a connection or to signal serious error conditions.
 * GOAWAY allows an endpoint to gracefully stop accepting new streams while still finishing processing of previously
 * established streams. This enables administrative actions, like server maintenance.
 *
 * <p>There is an inherent race condition between an endpoint starting new streams and the remote peer sending a GOAWAY
 * frame. To deal with this case, the GOAWAY contains the stream identifier of the last peer-initiated stream that was
 * or might be processed on the sending endpoint in this connection. For instance, if the server sends a GOAWAY frame,
 * the identified stream is the highest-numbered stream initiated by the client.
 *
 * <p>Once the GOAWAY is sent, the sender will ignore frames sent on streams initiated by the receiver if the stream has
 * an identifier higher than the included last stream identifier. Receivers of a GOAWAY frame MUST NOT open additional
 * streams on the connection, although a new connection can be established for new streams.
 *
 * <p>If the receiver of the GOAWAY has sent data on streams with a higher stream identifier than what is indicated in
 * the GOAWAY frame, those streams are not or will not be processed. The receiver of the GOAWAY frame can treat the
 * streams as though they had never been created at all, thereby allowing those streams to be retried later on a new
 * connection.
 *
 * <p>Endpoints SHOULD always send a GOAWAY frame before closing a connection so that the remote peer can know whether a
 * stream has been partially processed or not. For example, if an HTTP client sends a POST at the same time that a
 * server closes a connection, the client cannot know if the server started to process that POST request if the server
 * does not send a GOAWAY frame to indicate what streams it might have acted on.
 *
 * <p>An endpoint might choose to close a connection without sending a GOAWAY for misbehaving peers.
 *
 * <p>A GOAWAY frame might not immediately precede closing of the connection; a receiver of a GOAWAY that has no more
 * use for the connection SHOULD still send a GOAWAY frame before terminating the connection.
 *
 * <p>(End Spec)</p>
 */
public final class GoAwayFrame extends Frame {
    /**
     * The streamId of the last stream handled.
     *
     * <p>SPEC: 6.8
     * The last stream identifier in the GOAWAY frame contains the highest-numbered stream identifier for which the
     * sender of the GOAWAY frame might have taken some action on or might yet take action on. All streams up to and
     * including the identified stream might have been processed in some way. The last stream identifier can be set to
     * 0 if no streams were processed.
     */
    private int lastStreamId = -1;

    /**
     * The reasons for sending the GOAWAY frame. The default is NO_ERROR. This value will never be null.
     *
     * <p>The GOAWAY frame also contains a ... code (Section 7) that contains the reason for closing the connection.
     */
    private Http2ErrorCode errorCode = Http2ErrorCode.NO_ERROR;

    /**
     * Create a new instance.
     */
    public GoAwayFrame() {
        super(FrameType.GO_AWAY);
        setPayloadLength(8);
    }

    /**
     * Create a new instance.
     *
     * @param streamId The stream id must be non-negative.
     * @param lastStreamId The last stream ID that was successful
     * @param errorCode The error code associated with this frame
     */
    public GoAwayFrame(final int streamId, final int lastStreamId, final Http2ErrorCode errorCode) {
        super(8, FrameType.GO_AWAY, (byte) 0, streamId);
        this.lastStreamId = lastStreamId;
        this.errorCode = errorCode;
    }

    /**
     * Gets the error code associated with this frame.
     *
     * @return The error code. Will not be null.
     */
    public Http2ErrorCode getErrorCode() {
        return errorCode;
    }

    public GoAwayFrame setLastStreamId(int lastStreamId) {
        assert lastStreamId >= 0;
        this.lastStreamId = lastStreamId;
        return this;
    }

    public GoAwayFrame setErrorCode(Http2ErrorCode errorCode) {
        this.errorCode = Objects.requireNonNull(errorCode);
        return this;
    }

    public int getLastStreamId() {
        return lastStreamId;
    }

    @Override
    public void parse2(final InputBuffer in) {
        // Parse the headers
        super.parse2(in);

        // The stream ID, which *MUST* be zero
        final var streamId = getStreamId();
        if (streamId != 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        // If the payload length is not at least 8 bytes then we need to throw a FRAMING_ERROR
        // (although I cannot find it expressly in section 6.8 of the spec, this has to be
        // the way it is interpreted).
        final var payloadLength = getPayloadLength();
        if (payloadLength < 8) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, streamId);
        }

        // Parse the payload data. Note that Http2ErrorCode.fromOrdinal DOES NOT return null.
        // But maybe it should for forward compatibility, as the spec could add new error codes...
        this.lastStreamId = in.read31BitInteger();
        this.errorCode = Http2ErrorCode.fromOrdinal(in.read32BitInteger());

        // There may be arbitrary diagnostic data, which for now I'm just going to skip.
        if (payloadLength > 8) {
            in.skip(payloadLength - 8);
        }
    }

    /**
     * Write an RST_STREAM to the output.
     *
     * @param out The output stream. Cannot be null.
     */
    @Override
    public void write(OutputBuffer out) {
        // Write out the header.
        super.write(out);
        out.write32BitInteger(lastStreamId);
        out.write32BitUnsignedInteger(errorCode.ordinal());
    }
}
