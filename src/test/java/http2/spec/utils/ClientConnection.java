package http2.spec.utils;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Headers;
import com.hedera.hashgraph.web.impl.http2.frames.Frame;
import com.hedera.hashgraph.web.impl.http2.frames.Settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Defines a basic interface for sending and receiving data from an HTTP/2 server.
 * Implementations of this can be done using various technologies, including a simple
 * one that calls the server directly.
 */
public interface ClientConnection {
    int maxFrameSize();
    Settings serverSettings();
    boolean connectionClosed();

    ClientConnection handshake() throws IOException;
    ClientConnection send(byte[] data) throws IOException;
    ClientConnection send(int[] data) throws IOException;
    ClientConnection sendPing(boolean ack, byte[] data) throws IOException;
    ClientConnection sendHeaders(boolean endHeaders, boolean endStream, int streamId, Http2Headers headers) throws IOException;
    ClientConnection sendHeaders(boolean endHeaders, boolean endStream, int streamId, List<String> headers) throws IOException;
    ClientConnection sendData(boolean endStream, int streamId, byte[] payload) throws IOException;
    ClientConnection sendPriority(int streamId, int streamDep, boolean exclusive, int weight) throws IOException;
    ClientConnection sendContinuation(boolean endHeaders, int streamId, Http2Headers headers) throws IOException;
    ClientConnection sendRstStream(int streamId, Http2ErrorCode code) throws IOException;
    ClientConnection sendWindowUpdate(int streamId, int windowIncrement) throws IOException;
    ClientConnection sendSettings(Settings settings) throws IOException;

    <F extends Frame> F awaitFrame(Class<F> clazz) throws IOException;

    default ClientConnection sendData(boolean endStream, int streamId, String payload) throws IOException {
        return sendData(endStream, streamId, payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8));
    }
}
