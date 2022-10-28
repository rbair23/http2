package http2.spec;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Headers;
import com.hedera.hashgraph.web.impl.http2.frames.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("Section 6 :: Frame Definitions")
@Tag("4")
public class Section6SpecTest {

    @Nested
    @DisplayName("Section 6.1 :: DATA")
    @Tags({@Tag("6"), @Tag("6.1")})
    final class DataFrameTest extends SpecTest {
        /**
         * DATA frames MUST be associated with a stream. If a DATA frame is received whose stream identifier
         * field is 0x0, the recipient MUST respond with a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("Sends a DATA frame with 0x0 stream identifier")
        void sendDataFrameWithStream0() throws IOException {
            client.handshake()
                    .sendHeaders(true, false, 1, createCommonHeaders())
                    // Length: 12, Type: Data, Flags: End Stream, StreamID: 0
                    .send(new byte[] { 0x0, 0x0, 0xC, 0x1, 0x0, 0x0, 0x0, 0x0, 0x0 })
                    .send(randomString(12).getBytes());

            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * If a DATA frame is received whose stream is not in "open" or "half-closed (local)" state, the recipient
         * MUST respond with a stream error (Section 5.4.2) of type STREAM_CLOSED.
         *
         * <p>Note: This test case is duplicated with 5.1.
         */
        @Test
        @DisplayName("Sends a DATA frame on the stream that is not in \"open\" or \"half-closed (local)\" state")
        void sendDataFrameNotInOpen() throws IOException {
            client.handshake()
                    .sendHeaders(true, true, 1, createCommonHeaders().putMethod("POST"))
                    .sendData(true, 1, randomString(12));

            verifyStreamError(Http2ErrorCode.STREAM_CLOSED);
        }

        /**
         * If the length of the padding is the length of the frame payload or greater, the recipient MUST treat this
         * as a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("Sends a DATA frame with invalid pad length")
        void sendDataFrameWithInvalidPadLength() throws IOException {
            final var streamId = 1;
            final var headers = (Http2Headers) createCommonHeaders().putMethod("POST").put("content-length", "4");
            client.handshake()
                    .sendHeaders(true, false, streamId, headers)
                    // Length: 5, Type: DATA, Flags: Padded and EndStream, StreamId: 1
                    .send(new byte[] { 0x0, 0x0, 0x5, 0x0, 0x9, 0x0, 0x0, 0x0, 0x1 })
                    .send(new byte[] { 0x06, 0x54, 0x64, 0x73, 0x74 });

            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }
    }

    @Nested
    @DisplayName("Section 6.2 :: HEADERS")
    @Tags({@Tag("6"), @Tag("6.2")})
    final class HeadersFrameTest extends SpecTest {
        /**
         * END_HEADERS (0x4):<br>
         * A HEADERS frame without the END_HEADERS flag set MUST be followed by a CONTINUATION frame for the same
         * stream. A receiver MUST treat the receipt of any other type of frame or a frame on a different stream
         * as a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
         *
         * <p>Note: This test case is duplicated with 4.3.
         */
        @Test
        @DisplayName("Sends a HEADERS frame without the END_HEADERS flag, and a PRIORITY frame")
        void sendPriorityFrameBetweenHeadersAndContinuation() throws IOException {
            final var streamId = 1;
            client.handshake()
                    .sendHeaders(false, false, streamId, createCommonHeaders())
                    .sendPriority(streamId, 0, false, 255)
                    .sendContinuation(true, streamId, createDummyHeaders(96, 1));

            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * END_HEADERS (0x4):<br>
         * A HEADERS frame without the END_HEADERS flag set MUST be followed by a CONTINUATION frame for the same
         * stream. A receiver MUST treat the receipt of any other type of frame or a frame on a different stream
         * as a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
         *
         * <p>Note: This test case is duplicated with 4.3.
         */
        @Test
        @DisplayName("Sends a HEADERS frame to another stream while sending a HEADERS frame")
        void sendTwoHeadersWhileFirstIsNotDone() throws IOException {
            client.handshake()
                    .sendHeaders(false, false, 1, createCommonHeaders())
                    .sendHeaders(true, true, 3, createCommonHeaders())
                    .sendContinuation(true, 1, createDummyHeaders(10, 1));

            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * HEADERS frames MUST be associated with a stream. If a HEADERS frame is received whose stream identifier
         * field is 0x0, the recipient MUST respond with a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("Sends a HEADERS frame with 0x0 stream identifier")
        void sendHeadersForStream0() throws IOException {
            client.handshake()
                    // Length: 0, Type: HEADERS, Flags: EndHeaders and EndStream, StreamID: 0
                    .send(new byte[] { 0x0, 0x0, 0x0, 0x1, 0x5, 0x0, 0x0, 0x0, 0x0 });
            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * If the length of the padding is the length of the frame payload or greater, the recipient MUST treat this
         * as a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("Sends a HEADERS frame with invalid pad length")
        void sendHeadersFrameWithInvalidPadLength() throws IOException {
            client.handshake()
                    // Length: 0, Type: HEADERS, Flags: Padded and EndHeaders and EndStream, StreamId: 1
                    .send(new byte[] { 0x0, 0x0, 0x4, 0x1, 0xD, 0x0, 0x0, 0x0, 0x1 })
                    // Length is 4, PadLength is 6
                    .send(new byte[] { 0x6, 0x1, 0x2, 0x3, 0x4, 0x5, 0x6 });

            verifyStreamError(Http2ErrorCode.PROTOCOL_ERROR);
        }
    }

    @Nested
    @DisplayName("Section 6.3 :: PRIORITY")
    @Tags({@Tag("6"), @Tag("6.3")})
    final class PriorityFrameTest extends SpecTest {
        /**
         * The PRIORITY frame always identifies a stream. If a PRIORITY frame is received with a stream identifier
         * of 0x0, the recipient MUST respond with a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
         */
        @ParameterizedTest
        @ValueSource(ints = {0, 1})
        @DisplayName("Sends a PRIORITY frame with 0x0 stream identifier")
        void sendPriorityFrameWithStream0(int streamDep) throws IOException {
            client.handshake()
                    .sendPriority(0, streamDep, false, 255);

            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * A PRIORITY frame with a length other than 5 octets MUST be treated as a stream error (Section 5.4.2) of type
         * FRAME_SIZE_ERROR.
         */
        @Test
        @DisplayName("Sends a PRIORITY frame with a length other than 5 octets")
        void sendPriorityFrameWithLengthNot5() throws IOException {
            final var streamId = 1;
            client.handshake()
                    .sendHeaders(true, true, streamId, createCommonHeaders())
                    // Length: 4, Type: PRIORITY, Flags: 0, StreamId: 1
                    .send(new byte[] { 0x0, 0x0, 0x4, 0x2, 0x0, 0x0, 0x0, 0x0, 0x1 })
                    .send(new int[] { 0x80, 0x0, 0x0, 0x1 });

            verifyStreamError(Http2ErrorCode.FRAME_SIZE_ERROR);
        }
    }

    @Nested
    @DisplayName("Section 6.4 :: RST_STREAM")
    @Tags({@Tag("6"), @Tag("6.4")})
    final class RstStreamFrameTest extends SpecTest {
        /**
         * RST_STREAM frames MUST be associated with a stream.  If a RST_STREAM frame is received with a stream
         * identifier of 0x0, the recipient MUST treat this as a connection error (Section 5.4.1) of type
         * PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("Sends a RST_STREAM frame with 0x0 stream identifier")
        void sendRstStreamFrameWithStream0() throws IOException {
            client.handshake()
                    // Length: 4, Type: RST_STREAM, Flags: 0, StreamId: 0
                    .send(new byte[]{0x0, 0x0, 0x4, 0x3, 0x0, 0x0, 0x0, 0x0, 0x0})
                    .send(new byte[]{0x0, 0x0, 0x0, 0x8}); // CANCEL error code

            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * RST_STREAM frames MUST NOT be sent for a stream in the "idle" state. If a RST_STREAM frame identifying an
         * idle stream is received, the recipient MUST treat this as a connection error (Section 5.4.1) of type
         * PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("Sends a RST_STREAM frame on a idle stream")
        void sendRstStreamFrameOnIdleStream() throws IOException {
            client.handshake().sendRstStream(1, Http2ErrorCode.CANCEL);
            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * An RST_STREAM frame with a length other than 4 octets MUST be treated as a connection error
         * (Section 5.4.1) of type FRAME_SIZE_ERROR.
         */
        @Test
        @DisplayName("Sends a RST_STREAM frame with a length other than 4 octets")
        void sendRstStreamFrameWithWrongLength() throws IOException {
            client.handshake()
                    .sendHeaders(true, false, 1, createCommonHeaders())
                    // Length: 3, Type: RST_STREAM, Flags: 0, StreamId: 1
                    .send(new byte[]{0x0, 0x0, 0x3, 0x3, 0x0, 0x0, 0x0, 0x0, 0x1})
                    .send(new byte[]{0x0, 0x0, 0x8}); // CANCEL error code

            verifyStreamError(Http2ErrorCode.FRAME_SIZE_ERROR);
        }
    }

    @Nested
    @DisplayName("Section 6.5 :: SETTINGS")
    @Tags({@Tag("6"), @Tag("6.5")})
    final class SettingsFrameTest extends SpecTest {
        /**
         * ACK (0x1):<br>
         * When set, bit 0 indicates that this frame acknowledges receipt and application of the peer's SETTINGS frame.
         * When this bit is set, the payload of the SETTINGS frame MUST be empty. Receipt of a SETTINGS frame with the
         * ACK flag set and a length field value other than 0 MUST be treated as a connection error (Section 5.4.1)
         * of type FRAME_SIZE_ERROR.
         */
        @Test
        @DisplayName("Sends a SETTINGS frame with ACK flag and payload")
        void sendSettingsFrameWithAckAndPayload() throws IOException {
            client.handshake()
                    // Length:6, Type: SETTINGS, Flags: ACK, StreamId: 0
                    .send(new byte[] { 0x0, 0x0, 0x6, 0x4, 0x1, 0x0, 0x0, 0x0, 0x0 })
                    .send(new byte[] { 0x0, 0x3, 0x0, 0x0, 0x0, 0x64 });

            verifyConnectionError(Http2ErrorCode.FRAME_SIZE_ERROR);
        }

        /**
         * SETTINGS frames always apply to a connection, never a single stream. The stream identifier for a SETTINGS
         * frame MUST be zero (0x0). If an endpoint receives a SETTINGS frame whose stream identifier field is
         * anything other than 0x0, the endpoint MUST respond with a connection error (Section 5.4.1) of type
         * PROTOCOL_ERROR.
         */
        @ParameterizedTest
        @ValueSource(ints = { 1, 3, 11 })
        @DisplayName("Sends a SETTINGS frame with a stream identifier other than 0x0")
        void sendSettingsWithNonZeroStreamId(int streamId) throws IOException {
            client.handshake()
                    // Length:6, Type: SETTINGS, Flags: ACK, StreamId: 0
                    .send(new byte[] { 0x0, 0x0, 0x6, 0x4, 0x1, 0x0, 0x0, 0x0, (byte) streamId })
                    .send(new byte[] { 0x0, 0x3, 0x0, 0x0, 0x0, 0x64 });
            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * The SETTINGS frame affects connection state. A badly formed or incomplete SETTINGS frame MUST be treated
         * as a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
         *
         * <p>A SETTINGS frame with a length other than a multiple of 6 octets MUST be treated as a connection error
         * (Section 5.4.1) of type FRAME_SIZE_ERROR.
         */
        @ParameterizedTest
        @ValueSource(ints = { 1, 2, 3, 4, 5, 7, 8, 9, 10 })
        @DisplayName("Sends a SETTINGS frame with a length other than a multiple of 6 octets")
        void sendSettingsWithBadLengths(int length) throws IOException {
            client.handshake()
                    // Length:6, Type: SETTINGS, Flags: ACK, StreamId: 0
                    .send(new byte[] { 0x0, 0x0, (byte) length, 0x4, 0x1, 0x0, 0x0, 0x0, 0x1 })
                    .send(randomBytes(length));

            verifyStreamError(Http2ErrorCode.FRAME_SIZE_ERROR);
        }
    }

    @Nested
    @DisplayName("Section 6.7 :: PING")
    @Tags({@Tag("6"), @Tag("6.7")})
    final class PingFrameTest extends SpecTest {
        /**
         * Receivers of a PING frame that does not include an ACK flag MUST send a PING frame with the ACK flag set
         * in response, with an identical payload.
         */
        @Test
        @DisplayName("Sends a PING frame")
        void sendPingFrame() throws IOException {
            final var pingData = randomBytes(8);
            client.handshake()
                    .sendPing(false, pingData);

            verifyPingFrameWithAck(pingData);
        }

        /**
         * ACK (0x1):<br>
         * When set, bit 0 indicates that this PING frame is a PING response. An endpoint MUST set this flag in
         * PING responses. An endpoint MUST NOT respond to PING frames containing this flag.
         */
        @Test
        @DisplayName("Sends a SETTINGS frame with a stream identifier other than 0x0")
        void sendPingWithAckSetGetsNoResponse() throws IOException {
            final var unexpectedData = randomBytes(8);
            final var expectedData = randomBytes(8);
            client.handshake()
                    .sendPing(true, unexpectedData)
                    .sendPing(false, expectedData);

            verifyPingFrameWithAck(expectedData);
        }

        /**
         * If a PING frame is received with a stream identifier field value other than 0x0, the recipient MUST
         * respond with a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("Sends a PING frame with a stream identifier field value other than 0x0")
        void sendPingWithBadStreamIdentifier() throws IOException {
            client.handshake()
                    // Length:8, Type: PING, Flags: 0, StreamId: 1
                    .send(new byte[] { 0x0, 0x0, 0x8, 0x6, 0x0, 0x0, 0x0, 0x0, 0x1 })
                    .send(randomBytes(8));

            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * Receipt of a PING frame with a length field value other than 8 MUST be treated as a connection error
         * (Section 5.4.1) of type FRAME_SIZE_ERROR.
         */
        @ParameterizedTest
        @ValueSource(bytes = { 0, 1, 2, 3, 4, 5, 6, 7, 9, 10 })
        @DisplayName("Sends a PING frame with a length field value other than 8")
        void sendPingWithBadLength(byte length) throws IOException {
            client.handshake()
                    // Length: variable, Type: PING, Flags: 0, StreamId: 1
                    .send(new byte[] { 0x0, 0x0, length, 0x6, 0x0, 0x0, 0x0, 0x0, 0x0 })
                    .send(randomBytes(length));

            verifyConnectionError(Http2ErrorCode.FRAME_SIZE_ERROR);
        }
    }

    @Nested
    @DisplayName("Section 6.8 :: GOAWAY")
    @Tags({@Tag("6"), @Tag("6.8")})
    final class GoAwayFrameTest extends SpecTest {
        /**
         * An endpoint MUST treat a GOAWAY frame with a stream identifier other than 0x0 as a connection error
         * (Section 5.4.1) of type PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("Sends a GOAWAY frame with a stream identifier other than 0x0")
        void sendGoAwayWithBadStreamId() throws IOException {
            client.handshake()
                    // Length:8, Type: GOAWAY, Flags: 0, StreamId: 1
                    .send(new byte[] { 0x0, 0x0, 0x8, 0x7, 0x0, 0x0, 0x0, 0x0, 0x1 })
                    .send(randomBytes(8));

            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }
    }

    @Nested
    @DisplayName("Section 6.9 :: WINDOW_UPDATE")
    @Tags({@Tag("6"), @Tag("6.9")})
    final class WindowUpdateFrameTest extends SpecTest {
        /**
         * A receiver MUST treat the receipt of a WINDOW_UPDATE frame with a flow-control window increment of 0 as
         * a stream error (Section 5.4.2) of type PROTOCOL_ERROR; errors on the connection flow-control window MUST
         * be treated as a connection error (Section 5.4.1).
         */
        @Test
        @DisplayName("Sends a WINDOW_UPDATE frame with a flow control window increment of 0")
        void sendWindowUpdateToConnectionWithFlowControlIncrementOf0() throws IOException {
            client.handshake()
                    // Length:4, Type: WINDOW_UPDATE, Flags: 0, StreamId: 0, Increment: 0
                    .send(new byte[] { 0x0, 0x0, 0x4, 0x8, 0x0, 0x0, 0x0, 0x0, 0x0 })
                    .send(new byte[] { 0x0, 0x0, 0x0, 0x0 });

            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * A receiver MUST treat the receipt of a WINDOW_UPDATE frame with a flow-control window increment of 0 as
         * a stream error (Section 5.4.2) of type PROTOCOL_ERROR; errors on the connection flow-control window MUST
         * be treated as a connection error (Section 5.4.1).
         */
        @Test
        @DisplayName("Sends a WINDOW_UPDATE frame with a flow control window increment of 0 on a stream")
        void sendWindowUpdateToStreamWithFlowControlIncrementOf0() throws IOException {
            client.handshake()
                    .sendHeaders(true, false, 1, createCommonHeaders())
                    // Length:4, Type: WINDOW_UPDATE, Flags: 0, StreamId: 1, Increment: 0
                    .send(new byte[] { 0x0, 0x0, 0x4, 0x8, 0x0, 0x0, 0x0, 0x0, 0x1 })
                    .send(new byte[] { 0x0, 0x0, 0x0, 0x0 });

            verifyStreamError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * A WINDOW_UPDATE frame with a length other than 4 octets MUST be treated as a connection error
         * (Section 5.4.1) of type FRAME_SIZE_ERROR.
         */
        @ParameterizedTest
        @ValueSource(bytes = { 1, 2, 3, 5, 6, 7, 8 })
        @DisplayName("Sends a WINDOW_UPDATE frame with a length other than 4 octets")
        void sendWindowUpdateWithWrongFrameSize(byte length) throws IOException {
            client.handshake()
                    // Length:Variable, Type: WINDOW_UPDATE, Flags: 0, StreamId: 0, Increment: 1
                    .send(new byte[] { 0x0, 0x0, length, 0x8, 0x0, 0x0, 0x0, 0x0, 0x0 })
                    // NOTE: Technically the random bytes could be all zeros, which would fail for
                    // a different reason than this test exists for, so I will pick length - 1
                    // random bytes and send one final byte of 0x1, so I know the value wasn't 0.
                    .send(randomBytes(length - 1))
                    .send(new byte[] { 0x1 });

            verifyConnectionError(Http2ErrorCode.FRAME_SIZE_ERROR);
        }
    }

    @Nested
    @DisplayName("Section 6.9.1 :: The Flow-Control Window")
    @Tags({@Tag("6"), @Tag("6.9"), @Tag("6.9.1")})
    final class FlowControlWindowTest extends SpecTest {
        /**
         * The sender MUST NOT send a flow-controlled frame with a length that exceeds the space available in
         * either of the flow-control windows advertised by the receiver.
         */
        @Test
        @DisplayName("Sends SETTINGS frame to set the initial window size to 1 and sends HEADERS frame")
        void sendSettingsWithSmallInitialWindowSizeFollowedByHeaders() throws IOException {
            final var len = serverDataLength();
            assumeTrue(len > 0);

            final var settings = new Settings();
            settings.setInitialWindowSize(1);
            client.handshake().sendSettings(settings);

            verifySettingsFrameWithAck();

            client.sendHeaders(true, true, 1, createCommonHeaders());
            final var frame = client.awaitFrame(DataFrame.class);
            assertEquals(1, frame.getDataLength());
        }

        /**
         * A sender MUST NOT allow a flow-control window to exceed 2^31-1 octets. If a sender receives a
         * WINDOW_UPDATE that causes a flow-control window to exceed this maximum, it MUST terminate either
         * the stream or the connection, as appropriate. For streams, the sender sends a RST_STREAM with an
         * error code of FLOW_CONTROL_ERROR; for the connection, a GOAWAY frame with an error code of
         * FLOW_CONTROL_ERROR is sent.
         */
        @Test
        @DisplayName("Sends multiple WINDOW_UPDATE frames increasing the flow control window to above 2^31-1")
        void makeWindowFlowControlTooBig() throws IOException {
            client.handshake()
                    .sendWindowUpdate(0, (1 << 31) - 1)
                    .sendWindowUpdate(0, 1);
            final var frame = client.awaitFrame(GoAwayFrame.class);
            assertNotNull(frame);
        }

        /**
         * A sender MUST NOT allow a flow-control window to exceed 2^31-1 octets. If a sender receives a
         * WINDOW_UPDATE that causes a flow-control window to exceed this maximum, it MUST terminate either
         * the stream or the connection, as appropriate. For streams, the sender sends a RST_STREAM with an
         * error code of FLOW_CONTROL_ERROR; for the connection, a GOAWAY frame with an error code of
         * FLOW_CONTROL_ERROR is sent.
         */
        @Test
        @DisplayName("Sends multiple WINDOW_UPDATE frames increasing the flow control window to above 2^31-1 on a stream")
        void makeWindowFlowControlTooBigOnAStream() throws IOException {
            client.handshake()
                    .sendHeaders(true, false, 1, createCommonHeaders())
                    .sendWindowUpdate(1, (1 << 31) - 1)
                    .sendWindowUpdate(1, 1);
            final var frame = client.awaitFrame(RstStreamFrame.class);
            assertNotNull(frame);
        }
    }
}
