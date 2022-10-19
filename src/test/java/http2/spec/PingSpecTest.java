package http2.spec;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.frames.FrameType;
import com.hedera.hashgraph.web.impl.http2.frames.GoAwayFrame;
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

    /**
     * SPEC: 6.7<br>
     * Receivers of a PING frame that does not include an ACK flag MUST send a PING frame with the ACK
     * flag set in response, with an identical frame payload.
     */
    @Test
    void pingPong() throws IOException {
        client.submitPing(90210).sendAndReceive();
        var pong = client.receive(PingFrame.class);
        assertTrue(pong.isAck());
        assertEquals(90210, pong.getData());
    }

    /**
     * SPEC: 6.7<br>
     * PING frames are not associated with any individual stream. If a PING frame is received with a Stream
     * Identifier field value other than 0x00, the recipient MUST respond with a connection error (Section 5.4.1)
     * of type PROTOCOL_ERROR.
     */
    @Test
    void pingBadStreamId() throws IOException {
        client.submit(FrameType.PING, 0, 1, randomBytes(8))
                .sendAndReceive();

        final var goAway = client.receive(GoAwayFrame.class);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, goAway.getErrorCode());
    }

    /**
     * SPEC: 6.7<br>
     * Receipt of a PING frame with a length field value other than 8 MUST be treated as a connection error
     * (Section 5.4.1) of type FRAME_SIZE_ERROR.
     */
    @Test
    void pingBadLength() throws IOException {
        client.submit(FrameType.PING, 0, 0, null)
                .sendAndReceive();

        final var goAway = client.receive(GoAwayFrame.class);
        assertEquals(Http2ErrorCode.FRAME_SIZE_ERROR, goAway.getErrorCode());
    }

    // TODO I need to add to the server such that it will send a PING after a stream enters the CLOSED
    //      state. Then, the client (here) will have to acknowledge the PING, and I can make sure the
    //      server doesn't PING us again!
    //
    // For example, an endpoint that sends a SETTINGS frame after closing a stream can safely treat receipt of a DATA
    // frame on that stream as an error after receiving an acknowledgment of the settings. Other things that might be
    // used are PING frames, receiving data on streams that were created after closing the stream, or responses to
    // requests created after closing the stream.
}
