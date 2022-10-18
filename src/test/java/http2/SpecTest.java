package http2;

import com.hedera.hashgraph.web.WebRoutes;
import com.hedera.hashgraph.web.WebServerConfig;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.http2.Http2ConnectionImpl;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;
import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.ExecutorService;

abstract class SpecTest {
    protected static final String MALICIOUS = "Malicious";
    protected static final String PERFORMANCE = "Performance";
    protected static final String HAPPY_PATH = "Happy";
    protected static final String NEGATIVE = "Negative";

    protected static final int TEST_MAX_CONCURRENT_STREAMS_PER_CONNECTION = 42;

    protected Http2ConnectionImpl http2Connection;
    protected ExecutorService threadPool;
    protected MockByteChannel clientChannel;
    protected MockByteChannel serverChannel;
    protected OutputBuffer outputBuffer;
    protected InputBuffer inputBuffer;

    private final Random rand = new Random(9239992);

    @BeforeEach
    void setUp() {
        final var config = new WebServerConfig.Builder()
                .maxConcurrentStreamsPerConnection(TEST_MAX_CONCURRENT_STREAMS_PER_CONNECTION)
                .build();
        final var routes = new WebRoutes();
        threadPool = new MockExecutorService();
        final var dispatcher = new Dispatcher(routes, threadPool);
        final var contextReuseManager = new ContextReuseManager(dispatcher, config);
        http2Connection = new Http2ConnectionImpl(contextReuseManager, config);
        clientChannel = new MockByteChannel();
        serverChannel = new MockByteChannel();
        http2Connection.reset(serverChannel, null);
        outputBuffer = new OutputBuffer(1024 * 16);
        inputBuffer = new InputBuffer(1024 * 16);
    }

    protected void sendToServer() throws IOException {
        final ByteBuffer buffer = outputBuffer.getBuffer();
        buffer.flip();
        clientChannel.write(buffer);
        buffer.clear();
        clientChannel.sendTo(serverChannel);
    }

    protected void sendToClient() throws IOException {
        serverChannel.sendTo(clientChannel);
        inputBuffer.addData(clientChannel);
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

}
