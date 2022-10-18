package http2;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.frames.*;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;

import java.io.IOException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This sequence MUST be followed by a SETTINGS frame (Section 6.5), which MAY be empty. The client sends the
 * client connection preface as the first application data octets of a connection.
 *
 * <p>The server connection preface consists of a potentially empty SETTINGS frame (Section 6.5) that MUST be the
 * first frame the server sends in the HTTP/2 connection.
 *
 * <p>The SETTINGS frames received from a peer as part of the connection preface MUST be acknowledged (see Section
 * 6.5.3) after sending the connection preface.
 *
 * <p>To avoid unnecessary latency, clients are permitted to send additional frames to the server immediately after
 * sending the client connection preface, without waiting to receive the server connection preface
 *
 * <p>Clients and servers MUST treat an invalid connection preface as a connection error (Section 5.4.1) of type
 * PROTOCOL_ERROR
 */
@DisplayName("Section 3.4. HTTP/2 Connection Preface")
class ConnectionSpecTest extends SpecTest {

    @Test
    @Tag(HAPPY_PATH)
    @DisplayName("Client Settings Follows Preface")
    void settingsFollowsPreface() throws IOException {
        // Write a client settings frame and send it to the server
        SettingsFrame.write(outputBuffer, new Settings());
        doInitialSettingsFlow();
        // We MUST NOT receive another ACK from the server -- they shouldn't ACk the client ACK!!
        assertFalse(inputBuffer.available(1));
    }

    @Test
    @Tag(HAPPY_PATH)
    @DisplayName("Client Empty Settings Follows Preface")
    void emptySettingsFollowsPreface() throws IOException {
        // Write a client settings frame and send it to the server
        Frame.writeHeader(outputBuffer, 0, FrameType.SETTINGS, (byte) 0, 0);
        doInitialSettingsFlow();
        // We MUST NOT receive another ACK from the server -- they shouldn't ACk the client ACK!!
        assertFalse(inputBuffer.available(1));
    }

//    @ParameterizedTest()
//    @MethodSource("nonSettingsFrames")
    @Test
    @Tag(NEGATIVE)
    @DisplayName("Settings Does Not Follow Preface")
    void settingsIsSkipped() throws IOException {
        Frame.writeHeader(outputBuffer, 8, FrameType.PING, (byte) 0x1, 0);
        outputBuffer.write64BitLong(784388230L);
        sendToServer();

        // Let the server respond to the settings frame and send its response to the client
        http2Connection.handleIncomingData(version -> {});
        http2Connection.handleOutgoingData();
        sendToClient();

        // Read off the settings frame that came concurrently from the server
        // (The server MAY (and in our case, does) send server Settings before
        // processing the client settings.
        SettingsFrame.parseAndMerge(inputBuffer, new Settings());

        // We should have received a PROTOCOL_ERROR
        final var goAway = GoAwayFrame.parse(inputBuffer);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, goAway.getErrorCode());
    }

    @Test
    @Tag(HAPPY_PATH)
    @DisplayName("Client Sends Many Frames After It Sends Settings")
    void clientSendsManyFramesWithoutWaiting() throws IOException {
        // Send the settings
        SettingsFrame.write(outputBuffer, new Settings());

        // Send a ping
        Frame.writeHeader(outputBuffer, 8, FrameType.PING, (byte) 0x1, 0);
        outputBuffer.write64BitLong(784388230L);

        // Send some stuff
        final var tmp = new OutputBuffer(1024);
        for (int i = 1; i <= 10; i++) {
            // Send a header
            HeadersFrame.writeHeader(outputBuffer, i, 0);

            // Send some data
            // TODO If J gets big, I get problems. Not sure why.
            for (int j = 1; j <= 5; j++) {
                tmp.reset();
                tmp.write(randomBytes(256), 0, 256);
                DataFrame.write(outputBuffer, i, false, tmp);
            }

            // Send the last data
            DataFrame.writeLastData(outputBuffer, i);
        }

        doInitialSettingsFlow();
    }

    private void doInitialSettingsFlow() throws IOException {
        sendToServer();

        // Let the server respond to the settings frame and send its response to the client
        http2Connection.handleIncomingData(version -> {});
        http2Connection.handleOutgoingData();
        sendToClient();

        // On the client, read the settings frame from the server and make sure we actually
        // got settings from the server (TEST_... settings are set on the server by the SpecTest)
        final var serverSettings = new Settings();
        assertFalse(SettingsFrame.parseAndMerge(inputBuffer, serverSettings));
        assertEquals(TEST_MAX_CONCURRENT_STREAMS_PER_CONNECTION, serverSettings.getMaxConcurrentStreams());

        // On the client we should also have received the ACK settings object
        assertTrue(SettingsFrame.parseAndMerge(inputBuffer, serverSettings));

        // Send an ACK to the server that we received its settings
        SettingsFrame.writeAck(outputBuffer);
        sendToServer();
        http2Connection.handleIncomingData(version -> {});
    }

    private static Stream<Arguments> provideNonSettingsClientFrames() {
        return Stream.of(
                Arguments.of(new DataFrame(3, (byte) 0, 1, "Bad".getBytes())),
                Arguments.of(new HeadersFrame(0, (byte) 1, 1, new byte[0])),
                Arguments.of(new PriorityFrame(1)),
                Arguments.of(new PingFrame((byte) 0, 1234L)),
                Arguments.of(new GoAwayFrame(3, 1, Http2ErrorCode.CONNECT_ERROR)),
                Arguments.of(new WindowUpdateFrame(0, 124)),
                Arguments.of(new WindowUpdateFrame(1, 27)),
                Arguments.of(new ContinuationFrame((byte) 0, 1, "Data".getBytes()))
        );
    }

}
