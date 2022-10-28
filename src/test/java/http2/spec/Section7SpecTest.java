package http2.spec;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Headers;
import com.hedera.hashgraph.web.impl.http2.frames.DataFrame;
import com.hedera.hashgraph.web.impl.http2.frames.GoAwayFrame;
import com.hedera.hashgraph.web.impl.http2.frames.RstStreamFrame;
import com.hedera.hashgraph.web.impl.http2.frames.Settings;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@DisplayName("Section 7 :: Error Codes")
@Tag("7")
class Section7SpecTest extends SpecTest {

    /**
     * Unknown or unsupported error codes MUST NOT trigger any special behavior. These MAY be treated by an
     * implementation as being equivalent to INTERNAL_ERROR.
     */
    @Test
    @DisplayName("Sends a GOAWAY frame with unknown error code")
    void sendGoAwayWithUnknownErrorCode() throws IOException {
        final var pingData = randomBytes(8);
        client.handshake()
                // Length: 8, Type: GOAWAY, Flags: 0, StreamID: 0
                .send(new byte[] { 0x0, 0x0, 0x8, 0x7, 0x0, 0x0, 0x0, 0x0, 0x0 })
                // Payload is lastStreamId = 0, error code = some nonsense value
                .send(new int[] { 0x0, 0x0, 0x0, 0x0, 0x0, 0xFF, 0xFF, 0xFF, 0xFF })
                .sendPing(false, pingData);

        verifyPingFrameOrConnectionClose(pingData);
    }

    /**
     * Unknown or unsupported error codes MUST NOT trigger any special behavior. These MAY be treated by an
     * implementation as being equivalent to INTERNAL_ERROR.
     */
    @Test
    @DisplayName("Sends a RST_STREAM frame with unknown error code")
    void sendRstStreamWithUnknownErrorCode() throws IOException {
        final var pingData = randomBytes(8);
        client.handshake()
                .sendHeaders(true, false, 1, createCommonHeaders())
                // Length: 8, Type: RST_STREAM, Flags: 0, StreamID: 0
                .send(new byte[] { 0x0, 0x0, 0x4, 0x3, 0x0, 0x0, 0x0, 0x0, 0x1 })
                // Payload is error code = some nonsense value
                .send(new int[] { 0xFF, 0xFF, 0xFF, 0xFF })
                .sendPing(false, pingData);

        verifyPingFrameOrConnectionClose(pingData);
    }
}
