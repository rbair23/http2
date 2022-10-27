package http2.spec;

import com.hedera.hashgraph.web.WebRoutes;
import com.hedera.hashgraph.web.WebServerConfig;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.http2.Http2ConnectionImpl;
import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Headers;
import com.hedera.hashgraph.web.impl.http2.frames.*;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;
import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import http2.spec.utils.ClientConnection;
import http2.spec.utils.DirectClientConnection;
import http2.spec.utils.MockByteChannel;
import http2.spec.utils.MockExecutorService;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

abstract class SpecTest {
    protected static final String MALICIOUS = "Malicious";
    protected static final String PERFORMANCE = "Performance";
    protected static final String HAPPY_PATH = "Happy";
    protected static final String NEGATIVE = "Negative";

    private final Random rand = new Random(9239992);
    protected ClientConnection client;

    @BeforeEach
    void setUp() throws IOException {
        this.client = new DirectClientConnection(1, TimeUnit.SECONDS);
    }

    /**
     * Generates some random bytes
     *
     * @param length The number of bytes to generate.
     * @return Some random bytes.
     */
    protected byte[] randomBytes(int length) {
        final byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) rand.nextInt();
        }
        return data;
    }

    protected int randomInt() {
        return rand.nextInt();
    }

    protected String randomString(int length) {
        // https://www.baeldung.com/java-random-string
        return rand.ints(97, 122 + 1)
                .limit(length)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    protected Http2Headers createCommonHeaders() {
        final var h = new Http2Headers();
        h.putMethod("GET");
        h.putScheme("http"); // TODO get from config
        h.putPath("/"); // TODO get from config
        h.putAuthority("localhost"); // TODO get from config
        return h;
    }

    protected Http2Headers createDummyHeaders(int dummyStringLength, int numDummies) {
        final var headers = createCommonHeaders();
        for (int i = 0; i < numDummies; i++) {
            headers.put("x-dummy" + i, randomString(dummyStringLength));
        }
        return headers;
    }

    protected void verifyPingFrameWithAck(byte[] data) throws IOException {
        final var frame = client.awaitFrame(PingFrame.class);
        assertTrue(frame.isAck());
        assertArrayEquals(data, frame.getData());
    }

    protected void verifyHeadersFrame(int streamId) throws IOException {
        final var frame = client.awaitFrame(HeadersFrame.class);
        assertEquals(streamId, frame.getStreamId());
    }

    protected void verifyStreamError(Http2ErrorCode code) throws IOException {
        while (!client.connectionClosed()) {
            final var frame = client.awaitFrame(Frame.class);
            if (frame == null) {
                // A null frame means the connection was closed. Which is a good thing in this case.
                return;
            } if (frame instanceof RstStreamFrame rstFrame) {
                assertEquals(code, rstFrame.getErrorCode());
                return;
            } else if (frame instanceof GoAwayFrame goAwayFrame) {
                assertEquals(code, goAwayFrame.getErrorCode());
                return;
            }
        }
    }

    protected void verifyConnectionError(Http2ErrorCode code) throws IOException {
        final var frame = client.awaitFrame(GoAwayFrame.class);
        // A null frame means the connection was closed. Which is a good thing in this case.
        if (frame != null) {
            assertEquals(code, frame.getErrorCode());
        }
    }
}
