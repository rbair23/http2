package http2.spec.utils;

import com.hedera.hashgraph.web.StatusCode;
import com.hedera.hashgraph.web.WebRoutes;
import com.hedera.hashgraph.web.WebServerConfig;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.http2.Http2ConnectionImpl;
import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2HeaderCodec;
import com.hedera.hashgraph.web.impl.http2.Http2Headers;
import com.hedera.hashgraph.web.impl.http2.frames.*;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;
import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import com.twitter.hpack.Decoder;
import com.twitter.hpack.Encoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class DirectClientConnection implements ClientConnection {
    private MockByteChannel clientChannel;
    private OutputBuffer outputBuffer;
    private InputBuffer inputBuffer;
    private Server server;
    private Settings serverSettings;
    private LinkedList<Frame> receivedFrames = new LinkedList<>();
    private Encoder encoder = new Encoder(4096);
    private Decoder decoder = new Decoder(4096, 4096);
    private Http2HeaderCodec codec = new Http2HeaderCodec(encoder, decoder);

    private long timeout;
    private TimeUnit timeoutUnit;

    public DirectClientConnection(long timeout, TimeUnit unit) {
        clientChannel = new MockByteChannel();
        outputBuffer = new OutputBuffer(Settings.INITIAL_FRAME_SIZE * 2);
        inputBuffer = new InputBuffer(Settings.INITIAL_FRAME_SIZE * 2);
        this.server = new Server();
        this.timeout = timeout;
        this.timeoutUnit = unit == null ? TimeUnit.MILLISECONDS : unit;
    }

    @Override
    public int maxFrameSize() {
        return Settings.INITIAL_FRAME_SIZE;
    }

    @Override
    public Settings serverSettings() {
        return serverSettings;
    }

    @Override
    public boolean connectionClosed() {
        return !clientChannel.isOpen();
    }

    @Override
    public DirectClientConnection handshake() throws IOException {
        // Write a client settings frame and sendAndReceive it to the server
        sendSettings(new Settings()).sendAndReceive();
        final var serverSettingsFrame = awaitFrame(SettingsFrame.class);
        final var ignored = awaitFrame(SettingsFrame.class);
        sendSettingsAck(serverSettingsFrame);
        sendAndReceive();

        serverSettings = new Settings();
        serverSettings.setMaxHeaderListSize(serverSettingsFrame.getMaxHeaderListSize());
        serverSettings.setMaxConcurrentStreams(serverSettingsFrame.getMaxConcurrentStreams());
        serverSettings.setMaxFrameSize(serverSettingsFrame.getMaxFrameSize());
        serverSettings.setEnablePush(serverSettingsFrame.isEnablePush());
        serverSettings.setInitialWindowSize(serverSettingsFrame.getInitialWindowSize());
        serverSettings.setHeaderTableSize(serverSettingsFrame.getHeaderTableSize());

        // TODO rebuild the code possibly
        return this;
    }

    @Override
    public DirectClientConnection send(byte[] data) throws IOException {
        outputBuffer.write(data);
        sendAndReceive();
        return this;
    }

    @Override
    public DirectClientConnection send(int[] data) throws IOException {
        for (var b : data) {
            outputBuffer.write(b);
        }
        sendAndReceive();
        return this;
    }

    public DirectClientConnection send(Frame frame) throws IOException {
        frame.write(outputBuffer);
        sendAndReceive();
        return this;
    }

    @Override
    public DirectClientConnection sendPing(boolean ack, byte[] data) throws IOException {
        return send(new PingFrame(ack, data));
    }

    @Override
    public ClientConnection sendHeaders(boolean endHeaders, boolean endStream, int streamId, Http2Headers headers) throws IOException {
        final var out = new ByteArrayOutputStream();
        int length = codec.encode(headers, out);
        return send(new HeadersFrame(endHeaders, endStream, streamId, out.toByteArray(), length));
    }

    @Override
    public ClientConnection sendHeaders(boolean endHeaders, boolean endStream, int streamId, List<String> headers) throws IOException {
        assert headers.size() % 2 == 0;
        final var out = new ByteArrayOutputStream();
        for (int i = 0; i < headers.size(); i += 2) {
            final var key = headers.get(i).getBytes(StandardCharsets.UTF_8);
            final var value = headers.get(i + 1).getBytes(StandardCharsets.UTF_8);
            encoder.encodeHeader(out, key, value, false);
        }

        return send(new HeadersFrame(endHeaders, endStream, streamId, out.toByteArray(), out.size()));
    }

    @Override
    public ClientConnection sendData(boolean endStream, int streamId, byte[] payload) throws IOException {
        return send(new DataFrame(endStream, streamId, payload, payload.length));
    }

    @Override
    public ClientConnection sendPriority(int streamId, int streamDep, boolean exclusive, int weight) throws IOException {
        return send(new PriorityFrame(streamId, streamDep, exclusive, weight));
    }

    @Override
    public ClientConnection sendContinuation(boolean endHeaders, int streamId, Http2Headers headers) throws IOException {
        final var out = new ByteArrayOutputStream();
        int length = codec.encode(headers, out);
        return send(new ContinuationFrame(endHeaders, streamId, out.toByteArray(), length));
    }

    @Override
    public ClientConnection sendRstStream(int streamId, Http2ErrorCode code) throws IOException {
        return send(new RstStreamFrame(streamId, code));
    }

    @Override
    public ClientConnection sendWindowUpdate(int streamId, int windowIncrement) throws IOException {
        return send(new WindowUpdateFrame(streamId, windowIncrement));
    }

    @Override
    public DirectClientConnection sendSettings(Settings settings) throws IOException {
        return send(new SettingsFrame(settings));
    }

    public DirectClientConnection sendSettingsAck(SettingsFrame settings) {
        settings.writeAck(outputBuffer);
        return this;
    }

    @Override
    public <F extends Frame> F awaitFrame(Class<F> clazz) throws IOException {
        final var startTime = System.currentTimeMillis();
        final var endTime = startTime + timeoutUnit.toMillis(timeout);
        while (endTime > System.currentTimeMillis()) {
            // Give the server a chance to send more data to the client
            sendAndReceive();

            // If there is no chance of finding the frame I want, then return null
            if (receivedFrames.isEmpty() && connectionClosed()) {
                return null;
            }

            // Grab the next frame. If there isn't one, sleep for a bit
            final var frame = receivedFrames.poll();
            if (frame == null) {
                try {
                    TimeUnit.MILLISECONDS.sleep(10);
                } catch (InterruptedException ignored) {
                }
                continue;
            }

            // Is the frame the type I'm looking for?
            if (clazz.isAssignableFrom(frame.getClass())) {
                //noinspection unchecked
                return (F) frame;
            }
        }

        clientChannel.close();
        throw new AssertionError("Unable to find " + clazz.getSimpleName() + " in " + timeout + " " + timeoutUnit);
    }

//        public DirectClientConnection submit(FrameType type, int flags, int streamId, byte[] payload) {
//            return submit(type, flags, streamId, payload, 0, payload == null ? 0 : payload.length);
//        }
//
//        public DirectClientConnection submit(FrameType type, int flags, int streamId, byte[] payload, int offset, int length) {
//            outputBuffer.write24BitInteger(payload == null ? 0 : length);
//            outputBuffer.writeByte(type.ordinal());
//            outputBuffer.writeByte(flags);
//            outputBuffer.write32BitInteger(streamId);
//            if (payload != null) {
//                outputBuffer.write(payload, offset, length);
//            }
//            return this;
//        }
//
//
//    public DirectClientConnection submitEmptyHeaders(int streamId) {
////        return send(new HeadersFrame(true, false, streamId, new byte[0], 0));
//        return this;
//    }
//
//    public DirectClientConnection submitEmptyData(int streamId, boolean endOfStream, byte[] data) {
////        return send(new DataFrame(endOfStream, streamId, data, data.length));
//        return this;
//    }

    public void sendAndReceive() throws IOException {
        if (server.serverChannel.isOpen()) {
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

        if (!server.serverChannel.isOpen()) {
            clientChannel.close();
        }
    }

    public <T extends Frame> T receive() {
        //noinspection unchecked
        return (T) receivedFrames.remove();
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

    public DirectClientConnection clearReceivedFrames() {
        receivedFrames.clear();
        return this;
    }

    private final class Server {
        private static final int TEST_MAX_CONCURRENT_STREAMS_PER_CONNECTION = 42;
        private MockByteChannel serverChannel;
        private Http2ConnectionImpl http2Connection;
        private ExecutorService threadPool;

        public Server() {
            final var routes = new WebRoutes();
            routes.get("/", (req, res) -> {
                res.statusCode(StatusCode.OK_200)
                        .respond("text/plain", "I am sending some bytes");
            });
            routes.post("/", (req, res) -> {
                final byte[] data = req.getRequestBody().readAllBytes();
                res.respond(StatusCode.OK_200);
            });

            threadPool = Executors.newCachedThreadPool();
            final var dispatcher = new Dispatcher(routes, threadPool);
            final var config = new WebServerConfig.Builder()
                    .maxConcurrentStreamsPerConnection(TEST_MAX_CONCURRENT_STREAMS_PER_CONNECTION)
                    .build();
            final var contextReuseManager = new ContextReuseManager(dispatcher, config);
            http2Connection = new Http2ConnectionImpl(contextReuseManager, config);
            serverChannel = new MockByteChannel();
            http2Connection.reset(serverChannel);
        }

        public void send() throws IOException {
            serverChannel.sendTo(clientChannel);
            inputBuffer.addData(clientChannel);
        }
    }
}
