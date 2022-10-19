package http2.spec;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.frames.GoAwayFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A series of tests for Section 5 of the specification, "Streams and Multiplexing".
 */
@DisplayName("Section 5. Streams and Multiplexing")
class StreamsSpecTest extends SpecTest {

    // Sending a HEADERS frame as a client, or receiving a HEADERS frame as a server, causes the stream to become
    // "open". The stream identifier is selected as described in Section 5.1.1. The same HEADERS frame can also cause a
    // stream to immediately become "half-closed".
    //
    // TESTS:
    //     - Sending anything other than HEADERS (or PRIORITY) for a new stream results in a failure
    //         + Receiving any frame other than HEADERS or PRIORITY on a stream in this state MUST be treated as a
    //           connection error (Section 5.4.1) of type PROTOCOL_ERROR
    //     - Sending a HEADERS transitions to open, so any of the things that can be sent in the OPEN state will work
    //     - Sending a HEADERS with END_STREAM set goes to HALF_CLOSED, so anything that cannot be sent during
    //       HALF_CLOSED state will fail.

    // Sending a PUSH_PROMISE frame on another stream reserves the idle stream that is identified for later use. The
    // stream state for the reserved stream transitions to "reserved (local)". Only a server may sendAndReceive PUSH_PROMISE
    // frames.
    //
    // TESTS:
    //     - Sending a PUSH_PROMISE must fail (to any state, but let's verify for a new stream)

    // Opening a stream with a higher-valued stream identifier causes the stream to transition immediately to a
    // "closed" state; note that this transition is not shown in the diagram.
    //
    // NOT SURE WHAT THIS MEANS

    // A stream in the "open" state may be used by both peers to sendAndReceive frames of any type. In this state, sending peers
    // observe advertised stream-level flow-control limits
    //
    // NOTE: A test somewhere else should cover flow control
    // TESTS:
    //     - All different kinds of frames (other than HEADERS and CONTINUATION, unless HEADERS.END_HEADERS is false)
    //       can be sent

    // From this state, either endpoint can sendAndReceive a frame with an END_STREAM flag set, which causes the stream to
    // transition into one of the "half-closed" states. An endpoint sending an END_STREAM flag causes the stream state
    // to become "half-closed (local)"; an endpoint receiving an END_STREAM flag causes the stream state to become
    // "half-closed (remote)".
    //
    // TESTS:
    //     - While in an open state, Client sends each different type of frame that can have END_STREAM and verify that
    //       it gets some kind of response from the server acknowledging that.
    //     - In that state, most types of frames can no longer be sent (only WINDOW_UPDATE, PRIORITY, and RST_STREAM.)
    //         +  If an endpoint receives additional frames, other than WINDOW_UPDATE, PRIORITY, or RST_STREAM, for a
    //            stream that is in this state, it MUST respond with a stream error (Section 5.4.2) of type
    //            STREAM_CLOSED
    //         + A stream in this state will be closed if sent RST_STREAM

//    @Test
//    void something() throws IOException {
//        // Initializes the connection.
//        client.initializeConnection();
//
//        client.data(1, false, new byte[0]).sendAndReceive();
//        final var goAway = client.receiveGoAway();
//        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, goAway.getErrorCode());
//    }

    // .... LOTS MORE

    // SPEC: 5.1.1 Stream Identifiers
    //
    // Streams are identified by an unsigned 31-bit integer. Streams initiated by a client MUST use odd-numbered stream
    // identifiers;  ... the stream identifier of zero cannot be used to establish a new stream.
    //
    // The identifier of a newly established stream MUST be numerically greater than all streams that the initiating
    // endpoint has opened or reserved. This governs streams that are opened using a HEADERS frame and streams that are
    // reserved using PUSH_PROMISE. An endpoint that receives an unexpected stream identifier MUST respond with a
    // connection error (Section 5.4.1) of type PROTOCOL_ERROR.
    //
    // TESTS:
    //     - Create a Header with an even numbered ID and watch it fail with PROTOCOL_ERROR!
    //     - Also, with a streamId of 0!
    //     - Create a Header with a higher numbered ID and then create a Header with a lowered number ID

    @Test
    void headerWithStreamId0Fails() throws IOException {
        // Initializes the connection.
        client.initializeConnection();
        client.submitEmptyHeaders(0).sendAndReceive();
        final var goAway = client.receive(GoAwayFrame.class);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, goAway.getErrorCode());
    }

    @Test
    void headerWithStreamIdEvenFails() throws IOException {
        // Initializes the connection.
        client.initializeConnection();
        client.submitEmptyHeaders(2).sendAndReceive();
        final var goAway = client.receive(GoAwayFrame.class);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, goAway.getErrorCode());
    }

    // TODO I'm not sure this should actually fail. Just transitions to "closed" right away.
    //      Does that mean a response comes back?
    @Test
    void headerWithLowerStreamIdFails() throws IOException {
        // Initializes the connection.
        client.initializeConnection();
        client.submitEmptyHeaders(11).sendAndReceive();
        assertFalse(client.framesReceived());
        client.submitEmptyHeaders(3).sendAndReceive();
        final var goAway = client.receive(GoAwayFrame.class);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, goAway.getErrorCode());
    }

    /**
     * SPEC: 5.1.1 Stream Identifiers.
     *
     * <p>Stream identifiers cannot be reused. Long-lived connections can result in an endpoint exhausting
     * the available range of stream identifiers. A client that is unable to establish a new stream identifier
     * can establish a new connection for new streams. A server that is unable to establish a new stream identifier
     * can sendAndReceive a GOAWAY frame so that the client is forced to open a new connection for new streams.
     */
    @Test
    void headerWithMaxStreamIdFails() throws IOException {
        // Initializes the connection.
        client.initializeConnection();
        client.submitEmptyHeaders(Integer.MAX_VALUE - 2).sendAndReceive();
        assertFalse(client.framesReceived());
        client.submitEmptyHeaders(Integer.MAX_VALUE).sendAndReceive();
        final var goAway = client.receive(GoAwayFrame.class);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, goAway.getErrorCode());
    }

    // SPEC: 5.1.2 Stream Concurrency
    // A peer can limit the number of concurrently active streams using the SETTINGS_MAX_CONCURRENT_STREAMS
    // parameter (see Section 6.5.2) within a SETTINGS frame. The maximum concurrent streams setting is
    // specific to each endpoint and applies only to the peer that receives the setting. That is, clients
    // specify the maximum number of concurrent streams the server can initiate, and servers specify the
    // maximum number of concurrent streams the client can initiate.
    //
    // Streams that are in the "open" state or in either of the "half-closed" states count toward the maximum
    // number of streams that an endpoint is permitted to open. Streams in any of these three states count
    // toward the limit advertised in the SETTINGS_MAX_CONCURRENT_STREAMS setting. Streams in either of the
    // "reserved" states do not count toward the stream limit.
    //
    // Endpoints MUST NOT exceed the limit set by their peer. An endpoint that receives a HEADERS frame that
    // causes its advertised concurrent stream limit to be exceeded MUST treat this as a stream error
    // (Section 5.4.2) of type PROTOCOL_ERROR or REFUSED_STREAM. The choice of error code determines whether
    // the endpoint wishes to enable automatic retry (see Section 8.7 for details).
    //
    // TESTS:
    //     - Try to open more streams than allowed
    //     - Max out the number of connections, then let some of them enter "closed" state, then add more

    // TODO Flow Control

    // TODO Prioritization
}
