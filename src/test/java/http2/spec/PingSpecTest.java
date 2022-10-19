package http2.spec;

import com.hedera.hashgraph.web.impl.http2.frames.PingFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 */
@DisplayName("Section 6.7 PING")
class PingSpecTest extends SpecTest {

    @BeforeEach
    void setUp() throws IOException {
        super.setUp();
        client.initializeConnection();
    }

    // Section 6.7
    // Make sure we test that if the client pings, the client responds

    // TODO I need to add to the server such that it will send a PING after a stream enters the CLOSED
    //      state. Then, the client (here) will have to acknowledge the PING, and I can make sure the
    //      server doesn't PING us again!
    //
    // For example, an endpoint that sends a SETTINGS frame after closing a stream can safely treat receipt of a DATA
    // frame on that stream as an error after receiving an acknowledgment of the settings. Other things that might be
    // used are PING frames, receiving data on streams that were created after closing the stream, or responses to
    // requests created after closing the stream.

    @Test
    void pingPong() throws IOException {
        client.submitPing(90210).sendAndReceive();
        var pong = client.receive(PingFrame.class);
        assertTrue(pong.isAck());
        assertEquals(90210, pong.getData());
    }

    // TODO How to test this?
    //      PING frames are not associated with any individual stream. If a PING frame is received with a Stream
    //      Identifier field value other than 0x00, the recipient MUST respond with a connection error (Section 5.4.1)
    //      of type PROTOCOL_ERROR.

    // TODO how to test this?
    //      Receipt of a PING frame with a length field value other than 8 MUST be treated as a connection error
    //      (Section 5.4.1) of type FRAME_SIZE_ERROR.
}
