package http2.spec;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.frames.FrameType;
import com.hedera.hashgraph.web.impl.http2.frames.GoAwayFrame;
import com.hedera.hashgraph.web.impl.http2.frames.Settings;
import com.hedera.hashgraph.web.impl.http2.frames.SettingsFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Section 3.4 regarding the sequence for establishing a connection.
 */
@DisplayName("Section 3.4. HTTP/2 Connection Preface")
class ConnectionSpecTest extends SpecTest {

    /**
     * SPEC: 3.4<br>
     * This sequence MUST be followed by a SETTINGS frame (Section 6.5), which MAY be empty.
     */
    @Test
    @Tag(HAPPY_PATH)
    @DisplayName("Client Settings Follows Preface")
    void settingsFollowsPreface() throws IOException {
        // Write a client settings frame and send it to the server and get responses
        client.submitSettings(new Settings()).sendAndReceive();

        // We must not receive any error
        final var goAway = client.receiveOrNull(GoAwayFrame.class);
        assertNull(goAway);
    }

    /**
     * SPEC: 3.4<br>
     * This sequence MUST be followed by a SETTINGS frame (Section 6.5), which MAY be empty.
     */
    @Test
    @Tag(HAPPY_PATH)
    @DisplayName("Client Empty Settings Follows Preface")
    void emptySettingsFollowsPreface() throws IOException {
        // Write a client settings frame and send it to the server and get responses
        client.submitSettings(null).sendAndReceive();

        // We must not receive any error
        final var goAway = client.receiveOrNull(GoAwayFrame.class);
        assertNull(goAway);
    }

    /**
     * Spec: 3.4<br>
     * The server connection preface consists of a potentially empty SETTINGS frame (Section 6.5) that MUST be the
     * first frame the server sends in the HTTP/2 connection.
     */
    @Test
    @Tag(HAPPY_PATH)
    @DisplayName("Server's first response frame is Settings")
    void firstResponseIsSettings() throws IOException {
        // Write a client settings frame and send it to the server and get responses
        client.submitSettings(new Settings()).sendAndReceive();

        // On the client, read the settings frame from the server and make sure we actually
        // got settings from the server (TEST_... settings are set on the server by the SpecTest)
        final var serverSettingsFrame = client.receive(SettingsFrame.class);
        assertEquals(TEST_MAX_CONCURRENT_STREAMS_PER_CONNECTION, serverSettingsFrame.getMaxConcurrentStreams());
    }

    /**
     * SPEC: 3.4<br>
     * The SETTINGS frames received from a peer as part of the connection preface MUST be acknowledged
     * (see Section 6.5.3) after sending the connection preface.
     */
    @Test
    @Tag(HAPPY_PATH)
    @DisplayName("Server Acknowledges Client Settings Receipt")
    void serverAcknowledgesClientSettingsReceipt() throws IOException {
        // Write a client settings frame and send it to the server and get responses
        client.submitSettings(new Settings()).sendAndReceive();

        // The first frame returned is the server settings frame
        final var serverSettingsFrame = client.receive(SettingsFrame.class);
        assertFalse(serverSettingsFrame.isAck());

        // On the client we should also have received the ACK settings object
        final var ackSettingsFrame = client.receive(SettingsFrame.class);
        assertTrue(ackSettingsFrame.isAck());
    }

    /**
     * SPEC: 3.4<br>
     * To avoid unnecessary latency, clients are permitted to sendAndReceive additional frames to the server immediately
     * after sending the client connection preface, without waiting to receive the server connection preface
     */
    @Test
    @Tag(HAPPY_PATH)
    @DisplayName("Client Sends Many Frames After It Sends Settings")
    void clientSendsManyFramesWithoutWaiting() throws IOException {
        // Send the settings
        client.submitSettings(new Settings());

        // Send a ping
        client.submitPing(784388230L);

        // Send some stuff
        for (int i = 1; i <= 20; i+=2) {
            // Send a header
            client.submitEmptyHeaders(i);

            // Send some data
            // TODO If J gets big, I get problems. Not sure why.
            for (int j = 1; j <= 5; j++) {
                final var data = randomBytes(256);
                client.submitEmptyData(i, false, data);
            }

            // Send the last data
            client.submitEmptyData(i, true, new byte[0]);
        }

        client.sendAndReceive();

        // On the client, read the settings frame from the server and make sure we actually
        // got settings from the server (TEST_... settings are set on the server by the SpecTest)
        final var serverSettingsFrame = client.receive(SettingsFrame.class);
        assertEquals(TEST_MAX_CONCURRENT_STREAMS_PER_CONNECTION, serverSettingsFrame.getMaxConcurrentStreams());

        // On the client we should also have received the ACK settings object
        final var ackSettingsFrame = client.receive(SettingsFrame.class);
        assertTrue(ackSettingsFrame.isAck());
    }

    /**
     * Spec: 3.4<br>
     * Clients and servers MUST treat an invalid connection preface as a connection error (Section 5.4.1) of type
     * PROTOCOL_ERROR
     */
    @Test
    @Tag(NEGATIVE)
    @DisplayName("Settings Does Not Follow Preface")
    void settingsIsSkipped() throws IOException {
        // Submit a ping (and not a client settings)
        client.submitPing(784388230L).sendAndReceive();

        // We should have received a PROTOCOL_ERROR
        final var goAway = client.receive(GoAwayFrame.class);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, goAway.getErrorCode());
    }

    @Test
    @Tag(HAPPY_PATH)
    @DisplayName("Do Not Receive an ACK Storm")
    void noAckStorms() throws IOException {
        // Write a client settings frame and send it to the server and get responses
        client.submitSettings(new Settings()).sendAndReceive();

        // On the client, read the settings frame from the server and make sure we actually
        // got settings from the server (TEST_... settings are set on the server by the SpecTest)
        final var serverSettingsFrame = client.receive(SettingsFrame.class);
        assertEquals(TEST_MAX_CONCURRENT_STREAMS_PER_CONNECTION, serverSettingsFrame.getMaxConcurrentStreams());

        // On the client we should also have received the ACK settings object
        final var ackSettingsFrame = client.receive(SettingsFrame.class);
        assertTrue(ackSettingsFrame.isAck());

        // Send an ACK to the server that we received its settings
        client.submitSettingsAck(serverSettingsFrame).sendAndReceive();

        // We MUST NOT receive another ACK from the server -- they shouldn't ACK the client ACK!!
        assertFalse(client.framesReceived());
    }
}
