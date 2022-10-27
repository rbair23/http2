package http2.spec.utils;

import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.impl.http2.Http2Headers;
import com.hedera.hashgraph.web.impl.http2.frames.Frame;
import com.hedera.hashgraph.web.impl.http2.frames.FrameType;

import java.io.IOException;

/**
 * Defines a basic interface for sending and receiving data from an HTTP/2 server.
 * Implementations of this can be done using various technologies, including a simple
 * one that calls the server directly.
 */
public interface ClientConnection {
    int maxFrameSize();
    boolean connectionClosed();

    ClientConnection handshake() throws IOException;
    ClientConnection send(byte[] data) throws IOException;
    ClientConnection send(int[] data) throws IOException;
    ClientConnection sendPing(boolean ack, byte[] data) throws IOException;
    ClientConnection sendHeaders(boolean endHeaders, boolean endStream, int streamId, Http2Headers headers) throws IOException;
    ClientConnection sendData(boolean endStream, int streamId, byte[] payload) throws IOException;
    ClientConnection sendPriority(int streamId, int streamDep, boolean exclusive, int weight) throws IOException;
    ClientConnection sendContinuation(boolean endHeaders, int streamId, Http2Headers headers) throws IOException;

    <F extends Frame> F awaitFrame(Class<F> clazz) throws IOException;
}
