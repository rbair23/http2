package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.WebRoutes;
import com.hedera.hashgraph.web.impl.*;
import com.hedera.hashgraph.web.impl.http2.frames.*;
import com.twitter.hpack.Decoder;
import com.twitter.hpack.Encoder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

// Right now, this is created per-thread. To be reused across threads, we have to do some kind of per-thread state,
// such as for settings and request handlers.
public class Http2Protocol extends ProtocolBase {
    private static final int FRAME_HEADER_SIZE = 9;
    private static final int CONNECTION_STREAM_ID = 0;

    private static final int START = 0;
    private static final int AWAITING_SETTINGS = 1;
    private static final int AWAITING_FRAME = 2;
    private static final int READY_FOR_DISPATCH = 3;
    private static final int DISPATCHING = 4;
    private static final int DONE = 5;

    private final Settings clientSettings = new Settings();
    private final Settings serverSettings = new Settings();

    public Http2Protocol() {
        serverSettings.setMaxConcurrentStreams(10); // Spec recommends 100, at least. Maybe we can even do 1000 or something?
    }

    @Override
    public void handle(Dispatcher.ChannelData data, BiConsumer<Dispatcher.ChannelData, WebRequestImpl> doDispatch) {
        final var in = data.getIn();
        final var out = data.getOut();
        final var requestData = data.getRequestData();

        try {
            var needMoreData = false;
            while (!needMoreData) {
                switch (requestData.getState()) {
                    case START ->  {
                        System.out.println("New Stream");
                        // Send MY settings to the client (including the max stream number)
                        SettingsFrame.write(out, serverSettings);
                        requestData.setState(AWAITING_SETTINGS);
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

                            requestData.setState(AWAITING_FRAME);
                        } else {
                            needMoreData = true;
                        }
                    }
                    case AWAITING_FRAME -> {
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
                    case READY_FOR_DISPATCH -> {
                        // Go forth and handle. Good luck.
                        final var requestHeaders = requestData.getHeaders();
                        requestData.setPath(requestHeaders.get(":path"));
                        requestData.setMethod(requestHeaders.get(":method"));
                        requestData.setVersion("HTTP/2.0");
                        final var webRequest = new WebRequestImpl(requestData, (headers, responseCode) -> {
                            final var encoder = new Encoder((int) clientSettings.getHeaderTableSize());
                            final var hos = new RequestDataOutputStream(requestData);
                            encoder.encodeHeader(hos, ":status".getBytes(), ("" + responseCode).getBytes(), false);
                            final AtomicReference<IOException> ioException = new AtomicReference<>();
                            headers.forEach((k, v) -> {
                                try {
                                    encoder.encodeHeader(hos, k.getBytes(), v.getBytes(), false);
                                } catch (IOException e) {
                                    ioException.set(e);
                                }
                            });

                            final var e = ioException.get();
                            if (e != null) {
                                throw e;
                            } else {
                                HeadersFrame.write(out, requestData.getRequestId(), requestData.getData(), 0, hos.getCount());
                            }
                        });
                        doDispatch.accept(data, webRequest);
                        requestData.setState(DISPATCHING);
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

    @Override
    public void onServerError(Dispatcher.ChannelData channelData, WebRequestImpl request, RuntimeException ex) {
        // Who knows what happened? I got some random runtime exception, so it is a 500 class error
        // that needs to be returned.
    }

    @Override
    public void onEndOfRequest(final Dispatcher.ChannelData channelData, final WebRequestImpl request) {
        try {
            // Create the DataFrame to send to the client using the data within the RequestData object
            final var out = channelData.getOut();
            final var requestData = request.getRequestData();
            DataFrame.write(out, requestData.getRequestId(), true, requestData.getData(), 0, requestData.getDataLength());

            requestData.setState(DONE);

            // Close the stream.
//            RstStreamFrame.write(out, Http2ErrorCode.NO_ERROR, requestData.getRequestId());
            requestData.close();
        } catch (IOException fatalToConnection) {
            fatalToConnection.printStackTrace();
            channelData.close();
        }
    }

    @Override
    public void on404(Dispatcher.ChannelData channelData, WebRequestImpl request) {
        // Uh.... 404?
    }

    @Override
    protected void flush(Dispatcher.ChannelData channelData, Dispatcher.RequestData requestData) {
        // take data from the request data and write it to the output stream
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
                    case HEADERS -> handleHeaders(in, out, req);
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

    private void handleHeaders(HttpInputStream in, HttpOutputStream out, Dispatcher.RequestData requestData) throws IOException {
        final var headerFrame = HeadersFrame.parse(in);
        System.out.println("New header for request " + headerFrame.getStreamId());
        requestData.setRequestId(headerFrame.getStreamId());
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
            requestData.setHeaders(headers);
        }

        if (headerFrame.isEndStream()) {
            requestData.setState(READY_FOR_DISPATCH);
        }
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
