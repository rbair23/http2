package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.WebRoutes;
import com.hedera.hashgraph.web.impl.ProtocolHandler;
import com.hedera.hashgraph.web.impl.HttpInputStream;
import com.hedera.hashgraph.web.impl.HttpOutputStream;
import com.hedera.hashgraph.web.impl.http2.frames.*;

import java.io.EOFException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

// Right now, this is created per-thread. To be reused across threads, we have to do some kind of per-thread state,
// such as for settings and request handlers.
public class Http2ProtocolHandler implements ProtocolHandler {
    private static final byte[] CONNECTION_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes();
    private static final int CONNECTION_PREFACE_LENGTH = CONNECTION_PREFACE.length;
    private static final int FRAME_HEADER_SIZE = 9;

    private final Settings settings = new Settings();

    // Map of request id to request handler. Each frame that we read maps to a specific handler, key'd by id.
    private final Map<Integer, Http2RequestHandler> requestHandlers = new HashMap();
    private final Executor threadPool = Executors.newCachedThreadPool();

    public Http2ProtocolHandler(WebRoutes routes) {

    }

    @Override
    public void handle(HttpInputStream in, HttpOutputStream out) {

        try {
            // SPEC: 3.4 HTTP/2 Connection Preface
            // The client sends the client connection preface as the first application data octets of a connection.
            handlePreface(in, out);

            // SPEC: 3.4 HTTP/2 Connection Preface
            // That is, the connection preface ... MUST be followed by a SETTINGS frame
            handleSettings(in, out);

            // We will keep doing this until the input stream is closed.
            while (true) {
                handleFrame(in, out);
            }
        } catch (EOFException eof) {
            eof.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Http2Exception e) {
            // TODO write the response frame out for this error.
            e.printStackTrace();
        }
    }

    private void handlePreface(HttpInputStream in, HttpOutputStream out) throws IOException {
        // It turns out, the Java HTTP/2 client uses an old deprecated approach, where it sends an HTTP/1.1
        // Connect "upgrade" header. So even though it is deprecated as per the spec for RFC9113, we probably
        // should support it. Sigh. Not supporting that for now. But when I do, I will have a different set of
        // bytes to read off as if it were the preface


        // SPEC: 3.4 HTTP/2 Connection Preface
        // That is, the connection preface starts with the string "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".
        if (in.prefixMatch(CONNECTION_PREFACE)) {
            in.skip(CONNECTION_PREFACE_LENGTH);

            // SPEC: 3.4 HTTP/2 Connection Preface
            // That is, the connection preface ... MUST be followed by a SETTINGS frame...
            // Clients and servers MUST treat an invalid connection preface as a connection error (Section 5.4.1) of
            // type PROTOCOL_ERROR.
            final var nextFrameType = in.peekByte(3); // skip the next frame length
            if (nextFrameType != FrameTypes.SETTINGS.ordinal()) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
            }
        } else {
            // SPEC: 3.4 HTTP/2 Connection Preface
            // That is, the connection preface starts with the string "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".
            // Clients and servers MUST treat an invalid connection preface as a connection error (Section 5.4.1) of
            // type PROTOCOL_ERROR.
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR);
        }
    }

    // TODO Spec says this. How to enforce it for all frames? It seems dangerous to check it each and every method
    /*
        SPEC: 4.1 Frame Format
        The length of the frame payload expressed as an unsigned 24-bit integer in units of octets. Values greater
        than 2^14 (16,384) MUST NOT be sent unless the receiver has set a larger value for SETTINGS_MAX_FRAME_SIZE.

        Also, this suggests we should actively set flags to 0 that are not part of this spec version:
        Flags are assigned semantics specific to the indicated frame type. Unused flags are those that have no defined
        semantics for a particular frame type. Unused flags MUST be ignored on receipt and MUST be left
        unset (0x00) when sending.

        Also put this somewhere, whenever we're asking for 31 bit integer?
        A reserved 1-bit field. The semantics of this bit are undefined, and the bit MUST remain unset (0x00) when
        sending and MUST be ignored when receiving


     */


    private void handleFrame(HttpInputStream in, HttpOutputStream out) throws IOException {
        final var type = FrameTypes.fromOrdinal(in.peekByte(3));
        switch (type) {
            case SETTINGS -> handleSettings(in, out);
            case WINDOW_UPDATE -> handleWindowUpdate(in, out);
            case HEADERS -> handleHeaders(in, out);
            case RST_STREAM -> handleRstStream(in, out);
            case PING -> handlePing(in, out);
            // I don't know how to handle this one, so just skip it.

            // SPEC: 4.1 Frame Format
            // Implementations MUST ignore and discard frames of unknown types.
            default -> skipUnknownFrame(in);
        }
    }

    private void skipUnknownFrame(HttpInputStream in) throws IOException {
        final var frameLength = in.peek24BitInteger();
        in.skip(frameLength + FRAME_HEADER_SIZE);
    }

    private void handleSettings(HttpInputStream in, HttpOutputStream out) throws IOException {
        SettingsFrame.parseAndMerge(in, settings);
        SettingsFrame.writeAck(out);
    }

    private void handleWindowUpdate(HttpInputStream in, HttpOutputStream out) throws IOException {
        final var windowFrame = WindowUpdateFrame.parse(in);
        final var streamId = windowFrame.getStreamId();
        if (streamId != 0) {
            submitFrame(windowFrame, streamId, out);
        } else {
            // TODO need to do something with the default flow control settings for the connection...
        }
    }

    private void handleHeaders(HttpInputStream in, HttpOutputStream out) throws IOException {
        final var headerFrame = HeadersFrame.parse(in);
        submitFrame(headerFrame, headerFrame.getStreamId(), out);
    }

    private void submitFrame(Frame frame, int streamId, HttpOutputStream out) {
        final var handler = requestHandlers.computeIfAbsent(streamId, k -> {
            final var h = new Http2RequestHandler(out);
            threadPool.execute(h);
            return h;
        });

        handler.submit(frame);
    }

    private void handleRstStream(HttpInputStream in, HttpOutputStream out) throws IOException {
        final var frame = RstStreamFrame.parse(in);
        if (frame.getStreamId() > 0) {
            submitFrame(frame, frame.getStreamId(), out);
        }
    }

    private void handlePing(HttpInputStream in, HttpOutputStream out) throws IOException {
        final var frame = PingFrame.parse(in);
        if (frame.getStreamId() > 0) {
            submitFrame(frame, frame.getStreamId(), out);
        }
    }
}
