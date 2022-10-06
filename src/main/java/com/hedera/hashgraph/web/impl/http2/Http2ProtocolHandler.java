package com.hedera.hashgraph.web.impl.http2;

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
    private static final long FRAME_HEADER_SIZE = 9;

    private final Settings settings = new Settings();

    // Map of request id to request handler. Each frame that we read maps to a specific handler, key'd by id.
    private final Map<Integer, Http2RequestHandler> requestHandlers = new HashMap();
    private final Executor threadPool = Executors.newCachedThreadPool();

    @Override
    public void handle(HttpInputStream in, HttpOutputStream out) {

        try {
            // Check the preface to make sure it is good
            handlePreface(in);

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

    private void handlePreface(HttpInputStream in) throws IOException {
        // It turns out, the Java HTTP/2 client uses an old deprecated approach, where it sends an HTTP/1.1
        // Connect "upgrade" header. So even though it is deprecated as per the spec for RFC9113, we probably
        // should support it. Sigh. Not supporting that for now.
        if (in.prefixMatch(CONNECTION_PREFACE)) {
            in.skip(CONNECTION_PREFACE_LENGTH);
        } else {
            System.err.println("This is a serious error condition, we need to close the stream and so forth");
            // TODO Figure out how to handle error conditions
        }
    }

    private void handleFrame(HttpInputStream in, HttpOutputStream out) throws IOException {
        final var type = FrameTypes.fromOrdinal(in.pollByte(3));
        switch (type) {
            case SETTINGS -> handleSettings(in, out);
            case WINDOW_UPDATE -> handleWindowUpdate(in, out);
//            case HEADERS -> handleHeaders(in, out);
            // I don't know how to handle this one, so just skip it.
            default -> skipUnknownFrame(in);
        }
    }

    private void skipUnknownFrame(HttpInputStream in) throws IOException {
        final var frameLength = in.poll24BitInteger();
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
        }
    }

    private void handleHeaders(HttpInputStream in, HttpOutputStream out) throws IOException {
        final var frameLength = in.read24BitInteger();

        // Read past the type
        in.readByte();

        // TODO, keep track of the number of bytes read, and make sure they match frameLength perfectly

        var flags = in.readByte();
        final var priorityFlag = (flags & 0b0010_0000) != 0;
        final var paddedFlag = (flags & 0b0000_1000) != 0;
        final var endHeadersFlag = (flags & 0b0000_0100) != 0;
        final var endStreamFlag = (flags & 0b0000_0001) != 0;

        final var streamId = in.read31BitInteger();

        final var padLength = paddedFlag ? in.readByte() : 0;

        if (priorityFlag) {
            final var data = in.read32BitInteger();
            final var exclusive = (data & (Integer.MIN_VALUE)) != 0;
            final var streamDependency = data & 0x7FFFFFFF;
            final var weight = in.readByte();
        }

        // TODO Parse off the field block fragment

    }

    private void submitFrame(Frame frame, int streamId, HttpOutputStream out) {
        final var handler = requestHandlers.computeIfAbsent(streamId, k -> {
            final var h = new Http2RequestHandler(out);
            threadPool.execute(h);
            return h;
        });

        handler.submit(frame);
    }
}
