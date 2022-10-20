package http2.spec;

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
import java.util.LinkedList;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.concurrent.ExecutorService;

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
    void setUp() throws IOException {
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

    protected int randomInt() {
        return rand.nextInt();
    }

    protected final class Client {
        private MockByteChannel clientChannel;
        private OutputBuffer outputBuffer;
        private InputBuffer inputBuffer;
        private Server server;
        private LinkedList<Frame> receivedFrames = new LinkedList<>();

        public Client(Server server) {
            clientChannel = new MockByteChannel();
            outputBuffer = new OutputBuffer(1024 * 16);
            inputBuffer = new InputBuffer(1024 * 16);
            this.server = server;
            this.server.client = this;
        }

        public Client submit(FrameType type, int flags, int streamId, byte[] payload) {
            return submit(type, flags, streamId, payload, 0, payload == null ? 0 : payload.length);
        }

        public Client submit(FrameType type, int flags, int streamId, byte[] payload, int offset, int length) {
            outputBuffer.write24BitInteger(payload == null ? 0 : length);
            outputBuffer.writeByte(type.ordinal());
            outputBuffer.writeByte(flags);
            outputBuffer.write32BitInteger(streamId);
            if (payload != null) {
                outputBuffer.write(payload, offset, length);
            }
            return this;
        }

        public Client submit(Frame frame) {
            frame.write(outputBuffer);
            return this;
        }

        public Client submitSettings(Settings settings) {
            return submit(new SettingsFrame(settings));
        }

        public Client submitSettingsAck(SettingsFrame settings) {
            settings.writeAck(outputBuffer);
            return this;
        }

        public Client submitPing(long data) {
            return submit(new PingFrame(false, data));
        }

        public Client submitEmptyHeaders(int streamId) {
            return submit(new HeadersFrame(true, false, streamId, new byte[0], 0));
        }

        public Client submitEmptyData(int streamId, boolean endOfStream, byte[] data) {
            return submit(new DataFrame(endOfStream, streamId, data, data.length));
        }

        public void sendAndReceive() throws IOException {
            final ByteBuffer buffer = outputBuffer.getBuffer();
            buffer.flip();
            clientChannel.write(buffer);
            buffer.clear();
            clientChannel.sendTo(server.serverChannel);

            server.http2Connection.handleIncomingData(s -> {});
            server.http2Connection.handleOutgoingData();
            server.send();

            while (inputBuffer.available(9)) {
                final var ord = inputBuffer.peekByte(3);
                final var type = FrameType.valueOf(ord);
                final Frame frame = switch (type) {
                    case DATA -> new DataFrame();
                    case CONTINUATION -> new ContinuationFrame();
                    case PING -> new PingFrame();
                    case GO_AWAY -> new GoAwayFrame();
                    case HEADERS -> new HeadersFrame();
                    case PRIORITY -> new PriorityFrame();
                    case RST_STREAM -> new RstStreamFrame();
                    case SETTINGS -> new SettingsFrame();
                    case WINDOW_UPDATE -> new WindowUpdateFrame();
                    default -> null;
                };

                if (frame != null) {
                    frame.parse2(inputBuffer);
                    receivedFrames.add(frame);
                } else {
                    inputBuffer.skip(9 + inputBuffer.peek24BitInteger());
                }
            }
        }

        public void initializeConnection() throws IOException {
            // Write a client settings frame and sendAndReceive it to the server
            submitSettings(new Settings()).sendAndReceive();
            final var serverSettingsFrame = receive(SettingsFrame.class);
            final var ignored = receive(SettingsFrame.class);
            submitSettingsAck(serverSettingsFrame);
            sendAndReceive();
        }

        public <T extends Frame> T receive() {
            //noinspection unchecked
            return (T) receivedFrames.remove();
        }

        public <T extends Frame> T receive(Class<T> clazz) {
            final var frame = receiveOrNull(clazz);
            if (frame == null) {
                throw new NoSuchElementException("Could not find a frame of type " + clazz);
            }
            return frame;
        }

        public <T extends Frame> T receiveOrNull(Class<T> clazz) {
            while (!receivedFrames.isEmpty()) {
                final var frame = receivedFrames.remove();
                if (clazz.isAssignableFrom(frame.getClass())) {
                    //noinspection unchecked
                    return (T) frame;
                }
            }

            return null;
        }

        public boolean framesReceived() {
            return !receivedFrames.isEmpty();
        }

        public boolean serverRespondedWithFrame(FrameType frameType) {
            for (Frame f : receivedFrames) {
                if (f.getType() == frameType) {
                    return true;
                }
            }
            return false;
        }

        public Client clearReceivedFrames() {
            receivedFrames.clear();
            return this;
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
