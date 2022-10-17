package http2;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.frames.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void settingsFollowsPreface() {
        SettingsFrame.write(channel.getDataToSend(), new Settings());
        http2Connection.handle(version -> {});

        final var serverSettings = new Settings();
        SettingsFrame.parseAndMerge(channel.getDataReceived(), serverSettings);
        assertEquals(1000, serverSettings.getMaxConcurrentStreams());
    }

    @Test
    @Tag(HAPPY_PATH)
    @DisplayName("Client Empty Settings Follows Preface")
    void emptySettingsFollowsPreface() {

    }

    @ParameterizedTest()
    @MethodSource("nonSettingsFrames")
    @Tag(NEGATIVE)
    @DisplayName("Settings Does Not Follow Preface")
    void settingsIsSkipped(Frame frame) {
        // Expect a PROTOCOL_ERROR
    }

    @Test
    @Tag(HAPPY_PATH)
    @DisplayName("Server Acknowledges Client Settings")
    void serverAcknowledgesClientSettings() {

    }

    @Test
    @Tag(HAPPY_PATH)
    @DisplayName("Client Acknowledges Server Settings")
    void clientAcknowledgesServerSettings() {

    }

    @Test
    @Tag(HAPPY_PATH)
    @DisplayName("Client Sends Many Frames After It Sends Settings")
    void clientSendsManyFramesWithoutWaiting() {

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
