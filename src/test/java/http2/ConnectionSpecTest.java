package http2;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.frames.*;
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
        client.settings(new Settings())
                .send();

        // On the client, read the settings frame from the server and make sure we actually
        // got settings from the server (TEST_... settings are set on the server by the SpecTest)
        final var serverSettingsFrame = client.receiveSettings();
        assertEquals(TEST_MAX_CONCURRENT_STREAMS_PER_CONNECTION, serverSettingsFrame.getMaxConcurrentStreams());

        // On the client we should also have received the ACK settings object
        final var ackSettingsFrame = client.receiveSettings();
        assertTrue(ackSettingsFrame.isAck());

        // Send an ACK to the server that we received its settings
        client.ack(serverSettingsFrame);
        client.send();

        // We MUST NOT receive another ACK from the server -- they shouldn't ACK the client ACK!!
        assertFalse(client.framesReceived());
    }

    @Test
    @Tag(HAPPY_PATH)
    @DisplayName("Client Empty Settings Follows Preface")
    void emptySettingsFollowsPreface() throws IOException {
        // Write a client settings frame and send it to the server
        final var clientSettingsFrame = new SettingsFrame();
        client.settings(null).send();

        // On the client, read the settings frame from the server and make sure we actually
        // got settings from the server (TEST_... settings are set on the server by the SpecTest)
        final var serverSettingsFrame = client.receiveSettings();
        assertEquals(TEST_MAX_CONCURRENT_STREAMS_PER_CONNECTION, serverSettingsFrame.getMaxConcurrentStreams());

        // On the client we should also have received the ACK settings object
        final var ackSettingsFrame = client.receiveSettings();
        assertTrue(ackSettingsFrame.isAck());

        // Send an ACK to the server that we received its settings
        client.ack(serverSettingsFrame);
        client.send();

        // We MUST NOT receive another ACK from the server -- they shouldn't ACk the client ACK!!
        assertFalse(client.framesReceived());
    }

//    @Test
//    @Tag(NEGATIVE)
//    @DisplayName("Settings Does Not Follow Preface")
//    void settingsIsSkipped() throws IOException {
//        Frame.writeHeader(outputBuffer, 8, FrameType.PING, (byte) 0x1, 0);
//        outputBuffer.write64BitLong(784388230L);
//        sendToServer();
//
//        // Let the server respond to the settings frame and send its response to the client
//        http2Connection.handleIncomingData(version -> {});
//        http2Connection.handleOutgoingData();
//        sendToClient();
//
//        // Read off the settings frame that came concurrently from the server
//        // (The server MAY (and in our case, does) send server Settings before
//        // processing the client settings.
//        final var frame = new SettingsFrame(new Settings());
//        frame.parse2(inputBuffer);
//
//        // We should have received a PROTOCOL_ERROR
//        final var goAway = new GoAwayFrame();
//        goAway.parse2(inputBuffer);
//        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, goAway.getErrorCode());
//    }

    @Test
    @Tag(HAPPY_PATH)
    @DisplayName("Client Sends Many Frames After It Sends Settings")
    void clientSendsManyFramesWithoutWaiting() throws IOException {
        // Send the settings
        client.settings(new Settings());

        // Send a ping
        client.ping(784388230L);

        // Send some stuff
        for (int i = 1; i <= 10; i++) {
            // Send a header
            client.headers(i);

            // Send some data
            // TODO If J gets big, I get problems. Not sure why.
            for (int j = 1; j <= 5; j++) {
                final var data = randomBytes(256);
                client.data(i, false, data);
            }

            // Send the last data
            client.data(i, true, new byte[0]);
        }

        client.send();

        // On the client, read the settings frame from the server and make sure we actually
        // got settings from the server (TEST_... settings are set on the server by the SpecTest)
        final var serverSettingsFrame = client.receiveSettings();
        assertEquals(TEST_MAX_CONCURRENT_STREAMS_PER_CONNECTION, serverSettingsFrame.getMaxConcurrentStreams());

        // On the client we should also have received the ACK settings object
        final var ackSettingsFrame = client.receiveSettings();
        assertTrue(ackSettingsFrame.isAck());

        // Send an ACK to the server that we received its settings
        client.ack(serverSettingsFrame);
        client.send();
    }
}
