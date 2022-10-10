package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.WebRoutes;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.HttpInputStream;
import com.hedera.hashgraph.web.impl.HttpOutputStream;
import com.hedera.hashgraph.web.impl.ProtocolHandler;
import com.hedera.hashgraph.web.impl.http2.frames.*;
import com.twitter.hpack.Decoder;
import com.twitter.hpack.Encoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

// Right now, this is created per-thread. To be reused across threads, we have to do some kind of per-thread state,
// such as for settings and request handlers.
public class Http2ProtocolHandler implements ProtocolHandler {
    private static final int FRAME_HEADER_SIZE = 9;
    private static final int CONNECTION_STREAM_ID = 0;

    private static final int START = 0;
    private static final int AWAITING_SETTINGS = 1;
    private static final int AWAITING_FRAME = 2;

    private final Settings clientSettings = new Settings();
    private final Settings serverSettings = new Settings();

    private final Executor threadPool = Executors.newCachedThreadPool();
    private final WebRoutes routes;

    public Http2ProtocolHandler(WebRoutes routes) {
        this.routes = Objects.requireNonNull(routes);
        serverSettings.setMaxConcurrentStreams(10); // Spec recommends 100, at least. Maybe we can even do 1000 or something?
    }

    @Override
    public void handle(Dispatcher.ChannelData data, BiConsumer<Dispatcher.ChannelData, Dispatcher.RequestData> doDispatch) {
        final var in = data.getIn();
        final var out = data.getOut();
        final var requestData = data.getRequestData();

        try {
            var state = requestData.getState();
            var needMoreData = false;
            while (!needMoreData) {
                switch (state) {
                    case START ->  {
                        // Send MY settings to the client (including the max stream number)
                        SettingsFrame.write(out, serverSettings);
                        state = AWAITING_SETTINGS;
                    }
                    case AWAITING_SETTINGS -> {
                        if (in.available(FRAME_HEADER_SIZE)) {
                            // SPEC: 3.4 HTTP/2 Connection Preface
                            // That is, the connection preface ... MUST be followed by a SETTINGS frame...
                            // Clients and servers MUST treat an invalid connection preface as a connection error
                            // (Section 5.4.1) of type PROTOCOL_ERROR.
                            final var nextFrameType = in.peekByte(3);
                            if (nextFrameType != FrameType.SETTINGS.ordinal()) {
                                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, CONNECTION_STREAM_ID);
                            }

                            state = AWAITING_FRAME;
                        } else {
                            needMoreData = true;
                        }
                    }
                    default -> {
                        if (in.available(3)) {
                            int length = in.peek24BitInteger();
                            if (in.available(FRAME_HEADER_SIZE + length)) {
                                handleFrame(in, out, requestData);
                            } else {
                                needMoreData = true;
                            }
                        } else {
                            needMoreData = true;
                        }
                    }
                }
            }

            requestData.setState(state);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Http2Exception e) {
            try {
                RstStreamFrame.write(out, e.getCode(), e.getStreamId());
            } catch (IOException ee) {
                System.err.println("Failed to write RST_STREAM frame out, connection is broken!");
                // TODO Throw some kind of horrible error...
                ee.printStackTrace();
            }
        }
    }

    @Override
    public void handleError(Dispatcher.ChannelData channelData, Dispatcher.RequestData reqData, RuntimeException ex) {

    }

    @Override
    public void endOfRequest(Dispatcher.ChannelData channelData, Dispatcher.RequestData reqData) {

    }

    @Override
    public void handleNoHandlerError(Dispatcher.ChannelData channelData, Dispatcher.RequestData reqData) {

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

    private void handleFrame(HttpInputStream in, HttpOutputStream out, Dispatcher.RequestData req) throws IOException {
        if (in.available(3)) {
            int length = in.peek24BitInteger();
            if (in.available(FRAME_HEADER_SIZE + length)) {
                final var type = FrameType.valueOf(in.peekByte(3));
                switch (type) {
                    case SETTINGS -> handleSettings(in, out, req);
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
        }
    }

    private void skipUnknownFrame(HttpInputStream in) {
        final var frameLength = in.peek24BitInteger();
        in.skip(frameLength + FRAME_HEADER_SIZE);
    }

    private void handleSettings(HttpInputStream in, HttpOutputStream out, Dispatcher.RequestData req) throws IOException {
        // We need enough bytes to check the "type" field to confirm this is, in fact, a settings frame.
        if (in.available(4)) {
            // SPEC: 3.4 HTTP/2 Connection Preface
            // That is, the connection preface ... MUST be followed by a SETTINGS frame...
            // Clients and servers MUST treat an invalid connection preface as a connection error (Section 5.4.1) of
            // type PROTOCOL_ERROR.
            final var nextFrameType = in.peekByte(3); // skip the next frame length
            if (nextFrameType != FrameType.SETTINGS.ordinal()) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, CONNECTION_STREAM_ID);
            }

            // OK, it was a settings frame, so get the length and then make sure have enough bytes to process
            // the entire settings frame.
            final var length = in.peek24BitInteger();
            if (in.available(FRAME_HEADER_SIZE + length)) {
                SettingsFrame.parseAndMerge(in, clientSettings);
                SettingsFrame.writeAck(out);
                req.setState(AWAITING_FRAME);
            }
        }
    }

    private void handleWindowUpdate(HttpInputStream in, HttpOutputStream out) throws IOException {
        final var windowFrame = WindowUpdateFrame.parse(in);
        final var streamId = windowFrame.getStreamId();
        if (streamId != CONNECTION_STREAM_ID) {
            submitFrame(windowFrame, out);
        } else {
            // TODO need to do something with the default flow control settings for the connection...
        }
    }

    private void handleHeaders(HttpInputStream in, HttpOutputStream out) throws IOException {
        final var headerFrame = HeadersFrame.parse(in);
        if (headerFrame.isCompleteHeader()) {
            // I have all the bytes I will need... so I can go and decode them
            final var decoder = new Decoder(
                    (int) serverSettings.getMaxHeaderListSize(),
                    (int) serverSettings.getHeaderTableSize());

            decoder.decode(new ByteArrayInputStream(headerFrame.getFieldBlockFragment()), (name, value, sensitive) -> {
                // name is a byte[]
                // value is a byte[]
                // sensitive is a boolean
                System.out.println(new String(name) + " = " + new String(value));
            });
        }

        if (headerFrame.isEndStream()) {
            // Time to send my response!
            final var encoder = new Encoder((int) clientSettings.getHeaderTableSize());
            final var ooo = new ByteArrayOutputStream(1024);
            encoder.encodeHeader(ooo, ":status".getBytes(), "0".getBytes(), false);
            HeadersFrame.write(out, headerFrame.getStreamId(), ooo.toByteArray());

            DataFrame.write(out, headerFrame.getStreamId(), "Hello".getBytes());
        }
        submitFrame(headerFrame, out);
    }

    private void submitFrame(Frame frame, HttpOutputStream out) {
//        final var handler = requestHandlers.computeIfAbsent(streamId, k -> {
//            final var h = new Http2RequestHandler(out);
//            threadPool.execute(h);
//            return h;
//        });
//
//        handler.submit(frame);
    }

    private void handleRstStream(HttpInputStream in, HttpOutputStream out) throws IOException {
        final var frame = RstStreamFrame.parse(in);
        if (frame.getStreamId() > CONNECTION_STREAM_ID) {
            submitFrame(frame, out);
        }
    }

    private void handlePing(HttpInputStream in, HttpOutputStream out) throws IOException {
        final var frame = PingFrame.parse(in);
        if (frame.getStreamId() > CONNECTION_STREAM_ID) {
            submitFrame(frame, out);
        }
    }
}
