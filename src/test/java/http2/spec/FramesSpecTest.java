package http2.spec;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import org.junit.jupiter.api.*;

import java.io.IOException;

@DisplayName("Section 4 :: HTTP Frames")
@Tag("4")
public class FramesSpecTest {

    @Nested
    @DisplayName("Section 4.1 :: Frame Format")
    @Tags({@Tag("4"), @Tag("4.1")})
    final class FrameFormatTest extends SpecTest {
        /**
         * Type: The 8-bit type of the frame. The frame type determines the format and semantics of the frame.
         * Implementations MUST ignore and discard any frame that has a type that is unknown.
         */
        @Test
        @DisplayName("Sends a frame with unknown type")
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
         * Flags are assigned semantics specific to the indicated frame type. Flags that have no defined
         * semantics for a particular frame type MUST be ignored and MUST be left unset (0x0) when sending.
         */
        @Test
        @DisplayName("Sends a frame with undefined flag")
        // TODO Ideally we'd build this out with variations for all frame types
        void sendFrameWithUnknownFlags() throws IOException {
            final var pingData = randomBytes(8);
            client.handshake()
                    // PING frame with bogus flags
                    // Length: 8, Type: 6, Flags: 255, R: 0, StreamID: 0
                    .send(new byte[] { 0x0, 0x0, 0x8, 0x6, 0x16, 0x0, 0x0, 0x0, 0x0 })
                    .send(pingData);

            // Look for the ack ping frame
            verifyPingFrameWithAck(pingData);
        }

        /**
         * R: A reserved 1-bit field. The semantics of this bit are undefined, and the bit MUST remain unset (0x0)
         * when sending and MUST be ignored when receiving.
         */
        @Test
        @DisplayName("Sends a frame with reserved field bit")
        // TODO Ideally we'd build this out with variations for all frame types
        void sendFrameWithReservedFieldBit() throws IOException {
            final var pingData = randomBytes(8);
            client.handshake()
                    // PING frame with reserved bit set
                    // Length: 8, Type: 6, Flags: 255, R: 1, StreamID: 0
                    .send(new int[] { 0x0, 0x0, 0x8, 0x6, 0x16, 0x80, 0x0, 0x0, 0x0 })
                    .send(pingData);

            // Look for the ack ping frame
            verifyPingFrameWithAck(pingData);
        }
    }

    @Nested
    @DisplayName("Section 4.2 :: Frame Size")
    @Tags({@Tag("4"), @Tag("4.2")})
    final class FrameSizeTest extends SpecTest {
        /**
         * All implementations MUST be capable of receiving and minimally processing frames up to 2^14 octets in
         * length, plus the 9-octet frame header (Section 4.1).
         */
        @Test
        @DisplayName("Sends a DATA frame with 2^14 octets in length")
        void sendFrameWithFullData() throws IOException {
            final var streamId = 1;
            final var headers = createCommonHeaders().putMethod("POST");
            client.handshake()
                    .sendHeaders(true, false, streamId, headers)
                    .sendData(true, streamId, randomBytes(client.maxFrameSize()));

            verifyHeadersFrame(streamId);
        }

        /**
         * An endpoint MUST send an error code of FRAME_SIZE_ERROR if a frame exceeds the size defined in
         * SETTINGS_MAX_FRAME_SIZE, exceeds any limit defined for the frame type, or is too small to contain
         * mandatory frame data.
         */
        @Test
        @DisplayName("Sends a large size DATA frame that exceeds the SETTINGS_MAX_FRAME_SIZE")
        void sendFrameWithTooMuchData() throws IOException {
            final var streamId = 1;
            final var headers = createCommonHeaders().putMethod("POST");
            client.handshake()
                    .sendHeaders(true, false, streamId, headers)
                    .sendData(true, streamId, randomBytes(client.maxFrameSize() + 1));

            verifyStreamError(Http2ErrorCode.FRAME_SIZE_ERROR);
        }

        /**
         * A frame size error in a frame that could alter the state of the entire connection MUST be treated as
         * a connection error (Section 5.4.1); this includes any frame carrying a header block (Section 4.3)
         * (that is, HEADERS, PUSH_PROMISE, and CONTINUATION), SETTINGS, and any frame with a stream identifier of 0.
         */
        @Test
        @DisplayName("Sends a large size HEADERS frame that exceeds the SETTINGS_MAX_FRAME_SIZE")
        void sendFrameWithTooBigOfHeaders() throws IOException {
            final var streamId = 1;
            final var headers = createDummyHeaders(1024, 30);
            client.handshake()
                    .sendHeaders(true, true, streamId, headers);

            verifyConnectionError(Http2ErrorCode.FRAME_SIZE_ERROR);
        }
    }

    @Nested
    @DisplayName("Section 4.3 :: Header Compression and Decompression")
    @Tags({@Tag("4"), @Tag("4.3")})
    final class HeaderCompressionAndDecompressionTest extends SpecTest {

        // TODO Implement
        // A decoding error in a header block MUST be treated as
        // a connection error (Section 5.4.1) of type COMPRESSION_ERROR.
        // tg.AddTestCase(&spec.TestCase{
        // 	Desc:        "Sends invalid header block fragment",
        // 	Requirement: "The endpoint MUST terminate the connection with a connection error of type COMPRESSION_ERROR.",
        // 	Run: func(c *config.Config, conn *spec.Conn) error {
        // 		err := conn.Handshake()
        // 		if err != nil {
        // 			return err
        // 		}

        // 		// Literal Header Field with Incremental Indexing without
        // 		// Length and String segment.
        // 		err = conn.Send([]byte("\x00\x00\x01\x01\x05\x00\x00\x00\x01\x40"))
        // 		if err != nil {
        // 			return err
        // 		}

        // 		return spec.VerifyConnectionError(conn, http2.ErrCodeCompression)
        // 	},
        // })

        /**
         * Each header block is processed as a discrete unit. Header blocks MUST be transmitted as a contiguous
         * sequence of frames, with no interleaved frames of any other type or from any other stream.
         */
        @Test
        @DisplayName("Sends a PRIORITY frame while sending the header blocks")
        void sendPriorityFrameWhileSendingHeaderBlocks() throws IOException {
            final var streamId = 1;
            final var headers = createCommonHeaders();
            client.handshake()
                    .sendHeaders(false, false, streamId, headers)
                    .sendPriority(streamId, 0, false, 255)
                    .sendContinuation(true, streamId, createDummyHeaders(128, 1));

            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * Each header block is processed as a discrete unit. Header blocks MUST be transmitted as a contiguous
         * sequence of frames, with no interleaved frames of any other type or from any other stream.
         */
        @Test
        @DisplayName("Sends a HEADERS frame to another stream while sending the header blocks")
        void sendInterleavingHeaderFramesForDifferentStreams() throws IOException {
            final var streamId = 1;
            final var headers = createCommonHeaders();
            client.handshake()
                    .sendHeaders(false, false, streamId, headers)
                    .sendHeaders(true, true, streamId + 2, headers)
                    .sendContinuation(true, streamId, createDummyHeaders(128, 1));

            verifyConnectionError(Http2ErrorCode.PROTOCOL_ERROR);
        }
    }
}
