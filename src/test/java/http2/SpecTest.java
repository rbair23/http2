package http2;

import com.hedera.hashgraph.web.WebRoutes;
import com.hedera.hashgraph.web.WebServerConfig;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.http2.Http2ConnectionImpl;
import com.hedera.hashgraph.web.impl.http2.frames.*;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;
import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class SpecTest {
    protected static final String MALICIOUS = "Malicious";
    protected static final String PERFORMANCE = "Performance";
    protected static final String HAPPY_PATH = "Happy";
    protected static final String NEGATIVE = "Negative";

    protected static final int TEST_MAX_CONCURRENT_STREAMS_PER_CONNECTION = 42;

    private final Random rand = new Random(9239992);
    protected Client client;
    protected Server server;

    @BeforeEach
    void setUp() {
        this.server = new Server();
        this.client = new Client(this.server);
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

    protected final class Client {
        private MockByteChannel clientChannel;
        private OutputBuffer outputBuffer;
        private InputBuffer inputBuffer;
        private Server server;

        public Client(Server server) {
            clientChannel = new MockByteChannel();
            outputBuffer = new OutputBuffer(1024 * 16);
            inputBuffer = new InputBuffer(1024 * 16);
            this.server = server;
            this.server.client = this;
        }

        public Client settings(Settings settings) {
            final var clientSettingsFrame = new SettingsFrame(settings);
            clientSettingsFrame.write(outputBuffer);
            return this;
        }

        public Client ack(SettingsFrame settings) {
            settings.writeAck(outputBuffer);
            return this;
        }

        public Client ping(long data) {
            new PingFrame(false, data).write(outputBuffer);
            return this;
        }

        public Client headers(int streamId) {
            new HeadersFrame(false, streamId, new byte[0], 0)
                    .write(outputBuffer);
            return this;
        }

        public Client data(int streamId, boolean endOfStream, byte[] data) {
            new DataFrame(endOfStream, streamId, data, data.length)
                    .write(outputBuffer);
            return this;
        }

        public void exchangeSettings() throws IOException {
            // Write a client settings frame and send it to the server
            settings(new Settings()).send();
            final var serverSettingsFrame = receiveSettings();
            final var ignored = receiveSettings();
            ack(serverSettingsFrame);
            send();
        }

        public void send() throws IOException {
            final ByteBuffer buffer = outputBuffer.getBuffer();
            buffer.flip();
            clientChannel.write(buffer);
            buffer.clear();
            clientChannel.sendTo(server.serverChannel);

            server.http2Connection.handleIncomingData(s -> {});
            server.http2Connection.handleOutgoingData();
            server.send();
        }

        public SettingsFrame receiveSettings() {
            final var serverSettingsFrame = new SettingsFrame();
            serverSettingsFrame.parse2(inputBuffer);
            return serverSettingsFrame;
        }

        public GoAwayFrame receiveGoAway() {
            final var goAwayFrame = new GoAwayFrame();
            goAwayFrame.parse2(inputBuffer);
            return goAwayFrame;
        }

        public boolean framesReceived() {
            return inputBuffer.available(1);
        }
    }

    protected final class Server {
        private MockByteChannel serverChannel;
        private Http2ConnectionImpl http2Connection;
        private ExecutorService threadPool;
        private Client client;

        public Server() {
            final var routes = new WebRoutes();
            threadPool = new MockExecutorService();
            final var dispatcher = new Dispatcher(routes, threadPool);
            final var config = new WebServerConfig.Builder()
                    .maxConcurrentStreamsPerConnection(TEST_MAX_CONCURRENT_STREAMS_PER_CONNECTION)
                    .build();
            final var contextReuseManager = new ContextReuseManager(dispatcher, config);
            http2Connection = new Http2ConnectionImpl(contextReuseManager, config);
            serverChannel = new MockByteChannel();
            http2Connection.reset(serverChannel, null);
        }

        public void send() throws IOException {
            serverChannel.sendTo(client.clientChannel);
            client.inputBuffer.addData(client.clientChannel);
        }
    }
}
