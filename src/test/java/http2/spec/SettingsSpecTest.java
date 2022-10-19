package http2.spec;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.frames.*;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Section 6.5 of the spec.
 */
@DisplayName("Section 6.5 SETTINGS")
class SettingsSpecTest extends SpecTest {

    /**
     * SPEC: 6.5<br>
     * Receipt of a SETTINGS frame with the ACK flag set and a length field value other than 0 MUST be treated as a
     * connection error (Section 5.4.1) of type FRAME_SIZE_ERROR
     */
    @Test
    @Tag(NEGATIVE)
    @DisplayName("Client sends ACK with length > 0")
    void sendAckWithNonZeroPayloadLength() throws IOException {
        // Write a client settings frame and send it to the server and get responses
        client.submitSettings(null).sendAndReceive();

        // On the client, read the settings frame from the server and make sure we actually
        // got settings from the server (TEST_... settings are set on the server by the SpecTest)
        final var serverSettingsFrame = client.receive(SettingsFrame.class);
        assertEquals(TEST_MAX_CONCURRENT_STREAMS_PER_CONNECTION, serverSettingsFrame.getMaxConcurrentStreams());

        // On the client we should also have received the ACK settings object
        final var ackSettingsFrame = client.receive(SettingsFrame.class);
        assertTrue(ackSettingsFrame.isAck());
        assertEquals(0, ackSettingsFrame.getPayloadLength());

        // Send a BAD ACK to the server
        // NOTE: I pick a payload size that is a multiple of 6, otherwise I hit a *different*
        // error on the server and might easily think this test is working when it isn't!
        client.submit(FrameType.SETTINGS, (byte) 0x1, 0, randomBytes(6)).sendAndReceive();

        // We MUST get a FRAME_SIZE_ERROR
        final var goAway = client.receive(GoAwayFrame.class);
        assertEquals(Http2ErrorCode.FRAME_SIZE_ERROR, goAway.getErrorCode());
    }

    /**
     * SPEC: 6.5<br>
     * SETTINGS frames always apply to a connection, never a single stream. The stream identifier for a SETTINGS frame
     * MUST be zero (0x00). If an endpoint receives a SETTINGS frame whose Stream Identifier field is anything other
     * than 0x00, the endpoint MUST respond with a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
     */
    @Test
    @Tag(NEGATIVE)
    @DisplayName("Client sends Settings with a stream ID that is not 0")
    void sendSettingsWithStreamSpecified() throws IOException {
        // Write a client settings frame (that is empty, which is acceptable) but with
        // a settings that aims for a specific stream ID. This is an error.
        client.submit(FrameType.SETTINGS, (byte) 0x0, 1, new byte[0]).sendAndReceive();

        // We MUST get a PROTOCOL_ERROR
        final var goAway = client.receive(GoAwayFrame.class);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, goAway.getErrorCode());
    }

    @Test
    @Tag(NEGATIVE)
    @DisplayName("Client sends settings with a stream ID that is not 0 after connection is established")
    void sendSettingsWithStreamSpecifiedAfterConnectionEstablished() throws IOException {
        // Setup the connection properly
        client.initializeConnection();

        // Write a client settings frame (that is empty, which is acceptable) but with
        // a settings that aims for a specific stream ID. This is an error.
        client.submit(FrameType.SETTINGS, (byte) 0x0, 1, new byte[0]).sendAndReceive();

        // We MUST get a PROTOCOL_ERROR
        final var goAway = client.receive(GoAwayFrame.class);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, goAway.getErrorCode());
    }

    /**
     * SPEC: 6.5<br>
     * A SETTINGS frame with a length other than a multiple of 6 octets MUST be treated as a connection error
     * (Section 5.4.1) of type FRAME_SIZE_ERROR
     */
    @Test
    @Tag(NEGATIVE)
    @DisplayName("Client sends settings with length that is not a multiple of 6")
    void clientSendsSettingsWithLengthNotMultipleOf6() throws IOException {
        // Setup the connection properly
        client.initializeConnection();

        // Write a client settings frame with a length that is not a multiple of 6
        client.submit(FrameType.SETTINGS, (byte) 0x0, 0, new byte[4]).sendAndReceive();

        // We MUST get a PROTOCOL_ERROR
        final var goAway = client.receive(GoAwayFrame.class);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, goAway.getErrorCode());
    }

    /**
     * SPEC: 6.5.2<br>
     * An endpoint that receives a SETTINGS frame with any unknown or unsupported identifier MUST ignore that setting.
     */
    @Test
    @Tag(HAPPY_PATH)
    @DisplayName("Client sends settings with more than 6 possible values")
    void clientSendsSettingsWithMoreThan6Values() throws IOException {
        // Setup the connection properly
        client.initializeConnection();

        // To construct an invalid frame, first fill a buffer with normal settings bytes
        final var buf = new OutputBuffer(1024);
        final var normalSettingsFrame = new SettingsFrame(new Settings());
        normalSettingsFrame.write(buf);
        // Now, add to the end of that frame an extra key/value pair. The setting
        // ID can be anything greater than 6 and the value any random int
        buf.write16BigInteger(100);
        buf.write32BitInteger(randomInt());
        // Now get the array from the buffer and replace the "length" to include a 7th
        // setting
        final var arr = buf.getBuffer().array();
        arr[2] = 7 * 6;

        // Write the client settings including the extra unknown setting
        client.submit(FrameType.SETTINGS, (byte) 0x0, 0, arr).sendAndReceive();

        // We MUST NOT get an error of any kind. The implementation is forward compatible.
        final var goAway = client.receiveOrNull(GoAwayFrame.class);
        assertNull(goAway);
    }

    /**
     * SPEC: 6.5.2<br>
     * Any value other than 0 or 1 MUST be treated as a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
     */
    @ParameterizedTest
    @Tag(NEGATIVE)
    @DisplayName("An Enable Push Setting other than 0 or 1 fails")
    @ValueSource(ints = { 2, 4, Integer.MAX_VALUE })
    void badEnablePushValue(int value) throws IOException {
        // Write a client settings frame with "Enable Push" set to the specified value.
        // To do this, we will need to do something custom because our API only allows
        // a boolean (0 or 1). So we'll write a settings frame with a correct "enable push"
        // into a temporary buffer, get the byte array, and then hammer the value
        final var clientSettingsFrame = new SettingsFrame();
        clientSettingsFrame.setEnablePush(true);
        final var buf = new OutputBuffer(1024);
        clientSettingsFrame.write(buf);
        final var arr = buf.getBuffer().array();
        final var settingKeyIndex = Frame.FRAME_HEADER_SIZE;
        final var settingValueIndex = settingKeyIndex + 2;
        arr[settingValueIndex] = (byte) ((value >>> 24) & 0xFF);
        arr[settingValueIndex + 1] = (byte) ((value >>> 16) & 0xFF);
        arr[settingValueIndex + 2] = (byte) ((value >>> 8) & 0xFF);
        arr[settingValueIndex + 3] = (byte) (value & 0xFF);

        client.submit(FrameType.SETTINGS, (byte) 0x0, 0, arr, Frame.FRAME_HEADER_SIZE, 6)
                .sendAndReceive();

        // We MUST get a PROTOCOL_ERROR
        final var goAway = client.receive(GoAwayFrame.class);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, goAway.getErrorCode());
    }

    /**
     * "0" and "1" for "Enable Push" are good.
     *
     * <p>SPEC: 6.5.2<br>
     * Any value other than 0 or 1 MUST be treated as a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
     */
    @ParameterizedTest
    @Tag(HAPPY_PATH)
    @DisplayName("The Enable Push setting must either be 0 or 1")
    @ValueSource(booleans = { true, false })
    void enablePushIs0Or1(boolean value) throws IOException {
        final var clientSettingsFrame = new SettingsFrame();
        clientSettingsFrame.setEnablePush(value);
        client.submit(clientSettingsFrame).sendAndReceive();

        // We MUST NOT get an error of any kind. The implementation is forward compatible.
        final var goAway = client.receiveOrNull(GoAwayFrame.class);
        assertNull(goAway);
    }

    /**
     * SPEC: 6.5.2<br>
     * A server MAY choose to omit this setting when it sends a SETTINGS frame, but if a server does include a value,
     * it MUST be 0
     */
    @Test
    @Tag(HAPPY_PATH)
    @DisplayName("The Server Always Sends 0 for Push (if it sends it at all)")
    void enablePushIs0Or1() throws IOException {
        client.submitSettings(null).sendAndReceive();
        final var serverSettingsFrame = client.receive(SettingsFrame.class);
        assertFalse(serverSettingsFrame.isEnablePush());
    }

    // TODO How to verify that the server is actually using my client settings?
    //      How to verify that the server handles unsigned 32 bit integers correctly?

    /**
     * SPEC: 6.5.2<br>
     * [Initial Window Size] values above the maximum flow-control window size of (2^31)-1 MUST be treated as a
     * connection error (Section 5.4.1) of type FLOW_CONTROL_ERROR.
     */
    @ParameterizedTest
    @Tag(NEGATIVE)
    @DisplayName("Initial Window Size >= 2^31 - 1 results in a FLOW_CONTROL_ERROR")
    @ValueSource(longs = { (1L << 31), 0xFFFF_FFFFL, Long.MAX_VALUE, 0xFFFF_FFFF_FFFF_FFFFL })
    void tooLargeInitialWindowSizeThrows(long value) throws IOException {
        final var clientSettingsFrame = new SettingsFrame();
        clientSettingsFrame.setInitialWindowSize(value);
        client.submit(clientSettingsFrame).sendAndReceive();

        // We MUST get a FLOW_CONTROL_ERROR
        final var goAway = client.receive(GoAwayFrame.class);
        assertEquals(Http2ErrorCode.FLOW_CONTROL_ERROR, goAway.getErrorCode());
    }

    /**
     * SPEC: 6.5.2<br>
     * The initial value [of Max Frame Size] is 2^14 (16,384) octets. The value advertised by an endpoint MUST be
     * between this initial value and the maximum allowed frame size ((2^24)-1 or 16,777,215 octets), inclusive. Values
     * outside this range MUST be treated as a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
     */
    @ParameterizedTest
    @Tag(NEGATIVE)
    @DisplayName("Max Frame Size outside 2^14 and (2^24) - 1, inclusive is BAD")
    @ValueSource(ints = { 0, 20, 16384 - 1, 16_777_215 + 1, Integer.MAX_VALUE })
    void outOfBoundsMaxFrameSizeThrows(int value) throws IOException {
        final var clientSettingsFrame = new SettingsFrame();
        clientSettingsFrame.setMaxFrameSize(value);
        client.submit(clientSettingsFrame).sendAndReceive();

        // We MUST get a PROTOCOL_ERROR
        final var goAway = client.receive(GoAwayFrame.class);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, goAway.getErrorCode());
    }

    /**
     * SPEC: 6.5.2<br>
     * The initial value [of Max Frame Size] is 2^14 (16,384) octets. The value advertised by an endpoint MUST be
     * between this initial value and the maximum allowed frame size ((2^24)-1 or 16,777,215 octets), inclusive. Values
     * outside this range MUST be treated as a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
     */
    @ParameterizedTest
    @Tag(HAPPY_PATH)
    @DisplayName("Max Frame Size between 2^14 and (2^24) - 1, inclusive is GOOD")
    @ValueSource(ints = { 16_384, 32_000, 1_000_000, 16_777_215 })
    void validMaxFrameSizes(int value) throws IOException {
        final var clientSettingsFrame = new SettingsFrame();
        clientSettingsFrame.setMaxFrameSize(value);
        client.submit(clientSettingsFrame).sendAndReceive();

        // We MUST NOT get an error of any kind.
        final var goAway = client.receiveOrNull(GoAwayFrame.class);
        assertNull(goAway);
    }
}
