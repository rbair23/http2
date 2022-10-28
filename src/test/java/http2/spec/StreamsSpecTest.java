package http2.spec;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.frames.GoAwayFrame;
import com.hedera.hashgraph.web.impl.http2.frames.HeadersFrame;
import com.hedera.hashgraph.web.impl.http2.frames.Settings;
import org.junit.jupiter.api.*;

import java.io.IOException;

import static com.hedera.hashgraph.web.impl.http2.frames.Settings.DEFAULT_MAX_CONCURRENT_STREAMS;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A series of tests for Section 5 of the specification, "Streams and Multiplexing".
 */
@DisplayName("Section 5 :: Streams and Multiplexing")
@Tag("5")
class StreamsSpecTest extends SpecTest {

    @Nested
    @DisplayName("Section 5.1 :: Stream States")
    @Tags({@Tag("5"), @Tag("5.1")})
    final class StreamStatesTest extends SpecTest {
        /**
         * idle: Receiving any frame other than HEADERS or PRIORITY on a stream in this state MUST be treated
         * as a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("idle: Sends a DATA frame")
        void sendDataWhileIdle() throws IOException {
            client.handshake().sendData(true, 1, randomString(8));

            // This is an unclear part of the specification. Section 6.1 says
            // to treat this as a stream error.
            // --------
            // If a DATA frame is received whose stream is not in "open" or
            // "half-closed (local)" state, the recipient MUST respond with
            // a stream error (Section 5.4.2) of type STREAM_CLOSED.
            verifyStreamError(Http2ErrorCode.PROTOCOL_ERROR, Http2ErrorCode.STREAM_CLOSED);
        }

        /**
         * idle: Receiving any frame other than HEADERS or PRIORITY on a stream in this state MUST be treated
         * as a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("idle: Sends a RST_STREAM frame")
        void sendRstStreamWhileIdle() throws IOException {
            client.handshake().sendRstStream(1, Http2ErrorCode.CANCEL);
            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * idle: Receiving any frame other than HEADERS or PRIORITY on a stream in this state MUST be treated
         * as a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("idle: Sends a WINDOW_UPDATE frame")
        void sendWindowUpdateWhileIdle() throws IOException {
            client.handshake().sendWindowUpdate(1, 100);
            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * idle: Receiving any frame other than HEADERS or PRIORITY on a stream in this state MUST be treated
         * as a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("idle: Sends a CONTINUATION frame")
        void sendContinuationWhileIdle() throws IOException {
            client.handshake().sendContinuation(true, 1, createCommonHeaders());
            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * half-closed (remote): If an endpoint receives additional frames, other than WINDOW_UPDATE, PRIORITY, or
         * RST_STREAM, for a stream that is in this state, it MUST respond with a stream error (Section 5.4.2)
         * of type STREAM_CLOSED.
         */
        @Test
        @DisplayName("half closed (remote): Sends a DATA frame")
        void sendDataWhileHalfClosed() throws IOException {
            final var streamId = 1;
            client.handshake()
                    .sendHeaders(true, true, streamId, createCommonHeaders())
                    .sendData(true, streamId, randomString(12));
            verifyStreamError(Http2ErrorCode.STREAM_CLOSED);
        }

        /**
         * half-closed (remote): If an endpoint receives additional frames, other than WINDOW_UPDATE, PRIORITY, or
         * RST_STREAM, for a stream that is in this state, it MUST respond with a stream error (Section 5.4.2)
         * of type STREAM_CLOSED.
         */
        @Test
        @DisplayName("half closed (remote): Sends a HEADERS frame")
        void sendHeadersWhileHalfClosed() throws IOException {
            final var streamId = 1;
            client.handshake()
                    .sendHeaders(true, true, streamId, createCommonHeaders())
                    .sendHeaders(true, true, streamId, createCommonHeaders());
            verifyStreamError(Http2ErrorCode.STREAM_CLOSED);
        }

        /**
         * half-closed (remote): If an endpoint receives additional frames, other than WINDOW_UPDATE, PRIORITY, or
         * RST_STREAM, for a stream that is in this state, it MUST respond with a stream error (Section 5.4.2)
         * of type STREAM_CLOSED.
         */
        @Test
        @DisplayName("half closed (remote): Sends a CONTINUATION frame")
        void sendContinuationWhileHalfClosed() throws IOException {
            final var streamId = 1;
            client.handshake()
                    .sendHeaders(true, true, streamId, createCommonHeaders())
                    .sendContinuation(true, streamId, createCommonHeaders());
            verifyStreamError(Http2ErrorCode.STREAM_CLOSED);
        }

        /**
         * closed: An endpoint that receives any frame other than PRIORITY after receiving a RST_STREAM MUST treat
         * that as a stream error (Section 5.4.2) of type STREAM_CLOSED.
         */
        @Test
        @DisplayName("closed: Sends a DATA frame after sending RST_STREAM frame")
        void sendDataAfterRstStream() throws IOException {
            final var streamId = 1;
            client.handshake()
                    .sendHeaders(true, false, streamId, createCommonHeaders())
                    .sendRstStream(streamId, Http2ErrorCode.CANCEL)
                    .sendData(true, streamId, randomString(7));
            verifyStreamError(Http2ErrorCode.STREAM_CLOSED);
        }

        /**
         * closed: An endpoint that receives any frame other than PRIORITY after receiving a RST_STREAM MUST treat
         * that as a stream error (Section 5.4.2) of type STREAM_CLOSED.
         */
        @Test
        @DisplayName("closed: Sends a HEADERS frame after sending RST_STREAM frame")
        void sendHeadersAfterRstStream() throws IOException {
            final var streamId = 1;
            client.handshake()
                    .sendHeaders(true, false, streamId, createCommonHeaders())
                    .sendRstStream(streamId, Http2ErrorCode.CANCEL)
                    .sendHeaders(true, true, streamId, createCommonHeaders());
            verifyStreamError(Http2ErrorCode.STREAM_CLOSED);
        }

        /**
         * closed: An endpoint that receives any frame other than PRIORITY after receiving a RST_STREAM MUST treat
         * that as a stream error (Section 5.4.2) of type STREAM_CLOSED.
         */
        @Test
        @DisplayName("closed: Sends a CONTINUATION frame after sending RST_STREAM frame")
        void sendContinuationAfterRstStream() throws IOException {
            final var streamId = 1;
            client.handshake()
                    .sendHeaders(true, false, streamId, createCommonHeaders())
                    .sendRstStream(streamId, Http2ErrorCode.CANCEL)
                    .sendContinuation(true, streamId, createCommonHeaders());
            verifyStreamError(Http2ErrorCode.STREAM_CLOSED);
        }

        /**
         * closed: An endpoint that receives any frames after receiving a frame with the END_STREAM flag set
         * MUST treat that as a connection error (Section 6.4.1) of type STREAM_CLOSED.
         */
        @Test
        @DisplayName("closed: Sends a DATA frame")
        void sendDataWhileClosed() throws IOException {
            final var streamId = 1;
            client.handshake().sendHeaders(true, true, streamId, createCommonHeaders());
            verifyStreamClosed();
            client.sendData(true, streamId, randomString(21));
            verifyStreamError(Http2ErrorCode.STREAM_CLOSED);
        }

        /**
         * closed: An endpoint that receives any frames after receiving a frame with the END_STREAM flag set
         * MUST treat that as a connection error (Section 6.4.1) of type STREAM_CLOSED.
         */
        @Test
        @DisplayName("closed: Sends a HEADERS frame")
        void sendHeadersWhileClosed() throws IOException {
            final var streamId = 1;
            client.handshake().sendHeaders(true, true, streamId, createCommonHeaders());
            verifyStreamClosed();
            client.sendHeaders(true, true, streamId, createCommonHeaders());
            verifyStreamError(Http2ErrorCode.STREAM_CLOSED);
        }

        /**
         * closed: An endpoint that receives any frames after receiving a frame with the END_STREAM flag set
         * MUST treat that as a connection error (Section 6.4.1) of type STREAM_CLOSED.
         */
        @Test
        @DisplayName("closed: Sends a CONTINUATION frame")
        void sendContinuationWhileClosed() throws IOException {
            final var streamId = 1;
            client.handshake().sendHeaders(true, true, streamId, createCommonHeaders());
            verifyStreamClosed();
            client.sendContinuation(true, streamId, createCommonHeaders());
            verifyStreamError(Http2ErrorCode.STREAM_CLOSED);
        }
    }

    @Nested
    @DisplayName("Section 5.1.1 :: Stream Identifiers")
    @Tags({@Tag("5"), @Tag("5.1"), @Tag("5.1.1")})
    final class StreamIdentifiersTest extends SpecTest {
        /**
         * An endpoint that receives an unexpected stream identifier MUST respond with a connection error
         * (Section 5.4.1) of type PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("Sends even-numbered stream identifier")
        void sendEvenNumberedStreamId() throws IOException {
            client.handshake().sendHeaders(true, true, 2, createCommonHeaders());
            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * An endpoint that receives an unexpected stream identifier MUST respond with a connection error
         * (Section 5.4.1) of type PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("Sends stream identifier that is numerically smaller than previous")
        void sendSmallerStreamId() throws IOException {
            client.handshake()
                    .sendHeaders(true, true, 5, createCommonHeaders())
                    .sendHeaders(true, true, 3, createCommonHeaders());
            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * An endpoint that receives an unexpected stream identifier MUST respond with a connection error
         * (Section 5.4.1) of type PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("Sends stream identifier that is 0")
        void sendZeroStreamId() throws IOException {
            client.handshake()
                    // Length: 0, Type: HEADERS, Flags: End Stream, End Headers, StreamID: 0
                    .send(new byte[] { 0x0, 0x0, 0x0, 0x1, 0b0000_0101, 0x0, 0x0, 0x0, 0x0 });
            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }
    }

    @Nested
    @DisplayName("Section 5.1.2 :: Stream Concurrency")
    @Tags({@Tag("5"), @Tag("5.1"), @Tag("5.1.2")})
    final class StreamConcurrencyTest extends SpecTest {
        /**
         * An endpoint that receives a HEADERS frame that causes its advertised concurrent stream limit to be exceeded
         * MUST treat this as a stream error (Section 5.4.2) of type PROTOCOL_ERROR or REFUSED_STREAM.
         */
        @Test
        @DisplayName("Sends HEADERS frames that causes their advertised concurrent stream limit to be exceeded")
        void exceedConcurrentStreamLimit() throws IOException {
            client.handshake();

            // Don't bother running this test if the max concurrent streams is huge. Since it is often
            // set to the default setting, we can just check that (because it is unlikely the server chose
            // an unreasonably large value other than MAX)
            final var maxStreams = client.serverSettings().getMaxConcurrentStreams();
            assumeTrue(maxStreams < DEFAULT_MAX_CONCURRENT_STREAMS);

            // Set INITIAL_WINDOW_SIZE to zero to prevent the peer from closing the stream.
            final var settings = new Settings();
            settings.setInitialWindowSize(0);
            client.sendSettings(settings);

            // Create one more stream than the limit
            for (int i = 0; i < maxStreams + 1; i++) {
                client.sendHeaders(true, false, 1 + (2 * i), createCommonHeaders());
            }

            verifyStreamError(Http2ErrorCode.REFUSED_STREAM, Http2ErrorCode.PROTOCOL_ERROR);
        }
    }

    @Nested
    @DisplayName("Section 5.3.1 :: Stream Dependencies")
    @Tags({@Tag("5"), @Tag("5.3"), @Tag("5.3.1")})
    final class StreamDependencyTest extends SpecTest {
        /**
         * A stream cannot depend on itself. An endpoint MUST treat this as a stream error (Section 5.4.2) of
         * type PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("Sends HEADERS frame that depends on itself")
        void sendHeadersThatDependsOnItself() throws IOException {
            client.handshake()
                    // Length: 0, Type: HEADERS, Flags: Priority, End Stream, End Headers, StreamID: 1,
                    // Exclusive: false, Stream Dep: 1, Weight: 127
                    .send(new byte[] { 0x0, 0x0, 0x0, 0x1, 0b0010_0101, 0x0, 0x0, 0x0, 0x1, 0x0, 0x0, 0x0, 0x1, 0x7F });

            verifyStreamError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * A stream cannot depend on itself. An endpoint MUST treat this as a stream error (Section 5.4.2) of
         * type PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("Sends PRIORITY frame that depends on itself")
        void sendPriorityThatDependsOnItself() throws IOException {
            client.handshake().sendPriority(1, 1, false, 255);
            verifyStreamError(Http2ErrorCode.PROTOCOL_ERROR);
        }
    }

    @Nested
    @DisplayName("Section 5.4.1 :: Connection Error Handling")
    @Tags({@Tag("5"), @Tag("5.4"), @Tag("5.4.1")})
    final class ConnectionErrorHandlingTest extends SpecTest {
        /**
         * After sending the GOAWAY frame for an error condition, the endpoint MUST close the TCP connection.
         */
        @Test
        @DisplayName("Sends an invalid PING frame for connection close")
        void sendBadPingAndCloseConnection() throws IOException {
            client.handshake()
                    // Send invalid PING (target is stream 3 instead of stream 0)
                    .send(new byte[] { 0x0, 0x0, 0x8, 0x6, 0x0, 0x0, 0x0, 0x0, 0x3 })
                    .send(new byte[] { 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0 });

            verifyConnectionClosed();
        }

        /**
         * An endpoint that encounters a connection error SHOULD first send a GOAWAY frame (Section 6.8) with
         * the stream identifier of the last stream that it successfully received from its peer.
         */
        @Test
        @DisplayName("Sends an invalid PING frame to receive GOAWAY frame")
        void SendBadPingGetsGoAway() throws IOException {
            client.handshake()
                    // Send invalid PING (target is stream 3 instead of stream 0)
                    .send(new byte[] { 0x0, 0x0, 0x8, 0x6, 0x0, 0x0, 0x0, 0x0, 0x3 })
                    .send(new byte[] { 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0, 0x0 });

            assertNotNull(client.awaitFrame(GoAwayFrame.class), "GOAWAY frame not found");
        }
    }

    @Nested
    @DisplayName("Section 5.5 :: Extending HTTP/2")
    @Tags({@Tag("5"), @Tag("5.5")})
    final class ExtendingHttp2Test extends SpecTest {
        /**
         * Implementations MUST ignore unknown or unsupported values in all extensible protocol elements.
         * Note: This test case is duplicated with 4.1.
         */
        @Test
        @DisplayName("Sends an unknown extension frame")
        void sendFrameWithUnknownType() throws IOException {
            final var pingData = randomBytes(8);
            client.handshake()
                    // Length: 8, Type: 255, Flags: 0, R: 0, StreamID: 0
                    .send(new byte[] { 0x0, 0x0, 0x8, 0x16, 0x0, 0x0, 0x0, 0x0, 0x0 })
                    // Random 8 bytes of payload for the Unknown frame
                    .send(randomBytes(8))
                    .sendPing(false, pingData);

            // Look for the ack ping frame
            verifyPingFrameWithAck(pingData);
        }

        /**
         * Extension frames that appear in the middle of a header block (Section 4.3) are not permitted; these
         * MUST be treated as a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("Sends an unknown extension frame in the middle of a header block")
        void sendUnknownExtensionInHeaderBlock() throws IOException {
            final var streamId = 1;
            client.handshake()
                    .sendHeaders(false, true, streamId, createCommonHeaders())
                    // Length: 8, Type: 255, Flags: 0, R: 0, StreamID: 0
                    .send(new byte[] { 0x0, 0x0, 0x8, 0x16, 0x0, 0x0, 0x0, 0x0, 0x0 })
                    .send(randomBytes(8));

            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }
    }

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

}
