package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.impl.*;
import com.hedera.hashgraph.web.impl.http2.frames.*;
import com.hedera.hashgraph.web.impl.session.ConnectionContext;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import com.twitter.hpack.Decoder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

// Right now, this is created per-thread. To be reused across threads, we have to do some kind of per-thread state,
// such as for settings and request handlers.
public class Http2ConnectionContext extends ConnectionContext {
     
    private static final int FRAME_HEADER_SIZE = 9;
    private static final int CONNECTION_STREAM_ID = 0;

    private enum State {START, AWAITING_SETTINGS, AWAITING_FRAME, READY_FOR_DISPATCH, DISPATCHING, DONE};
    private State state;

    final Settings clientSettings = new Settings();
    final Settings serverSettings = new Settings();

    /**
     * This map of {@link Http2RequestContext} is used by the HTTP/2.0 implementation, where there are
     * multiple requests per channel. Cleared on {@link #close()}.
     */
    private Map<Integer, Http2RequestContext> multiStreamData = new HashMap<>();

    public Http2ConnectionContext(ContextReuseManager contextReuseManager, Dispatcher dispatcher) {
        super(contextReuseManager, dispatcher);
        serverSettings.setMaxConcurrentStreams(10); // Spec recommends 100, at least. Maybe we can even do 1000 or something?
    }

    @Override
    protected void reset() {
        super.reset();
        // TODO clientSettings.reset();
        // TODO serverSettings.reset();

    }

    @Override
    public void close() {
        super.close();
        // TODO Probably produces a bunch of garbage?
        this.multiStreamData.forEach((k, v) -> v.close());
        this.multiStreamData.clear();
    }

    @Override
    public void handle(Consumer<HttpVersion> upgradeConnectionCallback) {
// TODO where?       final Http2RequestContext requestSession = multiStreamData.get();

        try {
            var needMoreData = false;
            while (!needMoreData) {
                switch (state) {
                    case START ->  {
                        System.out.println("New Stream");
                        // Send MY settings to the client (including the max stream number)
                        channelSession.getOutputBuffer().reset();
                        SettingsFrame.write(channelSession.getOutputBuffer(), serverSettings);
                        channelSession.sendOutputData();

                        state = State.AWAITING_SETTINGS;
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

                            state = State.AWAITING_FRAME;
                        } else {
                            needMoreData = true;
                        }
                    }
                    case AWAITING_FRAME -> {
                        if (in.available(3)) {
                            int length = in.peek24BitInteger();
                            if (in.available(FRAME_HEADER_SIZE + length)) {
                                handleFrame(channelSession, requestSession);
                            } else {
                                needMoreData = true;
                            }
                        } else {
                            needMoreData = true;
                        }
                    }
                    case READY_FOR_DISPATCH -> {
                        // Go forth and handle. Good luck.
                        final var requestHeaders = requestSession.getHeaders();
                        requestSession.setPath(requestHeaders.get(":path"));
                        requestSession.setMethod(requestHeaders.get(":method"));
                        requestSession.setVersion("HTTP/2.0");
                        final var webRequest = new WebRequestImpl(requestSession, );
                        doDispatch.accept(channelSession, webRequest);
                        state = State.DISPATCHING;
                        return;
                    }
                    case DISPATCHING, DONE -> {
                        return;
                    }
                }
            }
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

    private void handleFrame(ConnectionContext channelSession, RequestSession requestSession) throws IOException {
        HttpInputStream in = channelSession.getIn();
        if (in.available(3)) {
            int length = in.peek24BitInteger();
            if (in.available(FRAME_HEADER_SIZE + length)) {
                final var type = FrameType.valueOf(in.peekByte(3));
                switch (type) {
                    case SETTINGS -> handleSettings(channelSession, requestSession);
                    case WINDOW_UPDATE -> handleWindowUpdate(channelSession);
                    case HEADERS -> handleHeaders(channelSession, requestSession);
                    case RST_STREAM -> handleRstStream(channelSession);
                    case PING -> handlePing(channelSession);
                    // I don't know how to handle this one, so just skip it.

                    // SPEC: 4.1 Frame Format
                    // Implementations MUST ignore and discard frames of unknown types.
                    default -> skipUnknownFrame(channelSession);
                }
            }
        }
    }

    private void skipUnknownFrame(ConnectionContext channelSession) {
        HttpInputStream in = channelSession.getIn();
        final var frameLength = in.peek24BitInteger();
        in.skip(frameLength + FRAME_HEADER_SIZE);
    }

    private void handleSettings(ConnectionContext channelSession, RequestSession requestSessioneq) throws IOException {
        HttpInputStream in = channelSession.getIn();
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
                OutputBuffer outputBuffer = channelSession.getOutputBuffer().reset();
                SettingsFrame.writeAck(outputBuffer);
                channelSession.sendOutputData();
                requestSessioneq.setState(AWAITING_FRAME);
            }
        }
    }

    private void handleWindowUpdate(ConnectionContext channelSession) throws IOException {
        HttpInputStream in = channelSession.getIn();
        final var windowFrame = WindowUpdateFrame.parse(in);
        final var streamId = windowFrame.getStreamId();
        if (streamId != CONNECTION_STREAM_ID) {
            OutputBuffer outputBuffer = channelSession.getOutputBuffer().reset();
            submitFrame(windowFrame, channelSession);
            channelSession.sendOutputData();
        } else {
            // TODO need to do something with the default flow control settings for the connection...
        }
    }

    private void handleHeaders(ConnectionContext channelSession, RequestSession requestSession) throws IOException {
        HttpInputStream in = channelSession.getIn();
        final var headerFrame = HeadersFrame.parse(in);
        System.out.println("New header for request " + headerFrame.getStreamId());
        requestSession.setRequestId(headerFrame.getStreamId());
        if (headerFrame.isCompleteHeader()) {
            // I have all the bytes I will need... so I can go and decode them
            final var decoder = new Decoder(
                    (int) serverSettings.getMaxHeaderListSize(),
                    (int) serverSettings.getHeaderTableSize());

            final var headers = new WebHeaders();
            decoder.decode(new ByteArrayInputStream(headerFrame.getFieldBlockFragment()), (name, value, sensitive) -> {
                // sensitive is a boolean
                headers.put(new String(name), new String(value));
            });
            requestSession.setHeaders(headers);
        }

        if (headerFrame.isEndStream()) {
            state = State.READY_FOR_DISPATCH;
        }
    }

    private void submitFrame(Frame frame, ConnectionContext channelSession) {
        final OutputBuffer outputBuffer = channelSession.getOutputBuffer().reset();
//        frame.
        channelSession.sendOutputData();
//        final var handler = requestHandlers.computeIfAbsent(streamId, k -> {
//            final var h = new Http2RequestHandler(out);
//            threadPool.execute(h);
//            return h;
//        });
//
//        handler.submit(frame);
    }

    private void handleRstStream(ConnectionContext channelSession) throws IOException {
        final var frame = RstStreamFrame.parse(channelSession.getIn());
        if (frame.getStreamId() > CONNECTION_STREAM_ID) {
            submitFrame(frame, channelSession);
        }
    }

    private void handlePing(ConnectionContext channelSession) throws IOException {
        final var frame = PingFrame.parse(channelSession.getIn());
        if (frame.getStreamId() > CONNECTION_STREAM_ID) {
            submitFrame(frame, channelSession);
        }
    }
}
