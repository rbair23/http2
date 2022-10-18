package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.HttpVersion;
import com.hedera.hashgraph.web.WebServerConfig;
import com.hedera.hashgraph.web.impl.DataHandler;
import com.hedera.hashgraph.web.impl.http.Http1ConnectionContext;
import com.hedera.hashgraph.web.impl.http2.frames.*;
import com.hedera.hashgraph.web.impl.session.ConnectionContext;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;
import com.hedera.hashgraph.web.impl.session.HandleResponse;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import com.twitter.hpack.Decoder;
import com.twitter.hpack.Encoder;

import java.nio.channels.ByteChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * An implementation of {@link ConnectionContext} representing the physical connection for an HTTP/2
 * request. When this context is closed, the corresponding channel is also closed.
 * <p>
 * The HTTP/2 input is a set of "frames", with each frame either belonging to the connection itself,
 * or to one or more "streams". Each "stream" is represented by the {@link Http2Stream}. Frames
 * for different streams, or for the connection itself, are interleaved on the wire. The context needs
 * to decode the frames, determine the stream for the frame, create if necessary an {@link Http2Stream},
 * and delegate to it.
 */
public final class Http2ConnectionImpl extends ConnectionContext implements Http2Connection {
    /**
     * Every frame in HTTP/2.0 is the same size -- 9 bytes.
     */
    private static final int FRAME_HEADER_SIZE = 9;

    /**
     * Each frame defines the stream that the frame belongs to. The stream id of "0" denotes
     * frames that belong to the connection itself.
     */
    private static final int CONNECTION_STREAM_ID = 0;

    /**
     * Defines the different states in the state machine for processing the request, such that
     * as bytes are read from the input, we behave differently depending on which state we're in.
     */
    private enum State {
        /**
         * Initial state for the {@link Http2ConnectionImpl}. In the start state, we send our
         * server settings to the client.
         */
        START,
        /**
         * After START, we have sent our settings to the client, and we're ready to receive settings
         * from the client (which might already be waiting on the wire for us to read).
         */
        AWAITING_SETTINGS,
        /**
         * After exchanging settings, we're fully open for business.
         */
        OPEN,
        /**
         * The terminal state, reached when the connection is terminated for any reason.
         */
        CLOSED
    }

    /**
     * The current state of this connection.
     */
    private State state = State.START;

    /**
     * The settings that the server sends to the client. The client must respect these settings.
     * These settings, once set, are never altered during the run of the server. (Theoretically
     * they could be, but we don't support that at this time).
     */
    private final Settings serverSettings = new Settings();

    /**
     * The settings sent from the client to the server. We start off with a default set of settings,
     * and when the client connects, it must send its settings. Those settings sent by the client
     * will override the defaults here. When the context is closed, these settings are set back to
     * their default values.
     */
    private final Settings clientSettings = new Settings();

    /**
     * The codec to use for encoding / decoding the header data. Rebuilt when settings change
     * in a way that impacts the codec.
     */
    private Http2HeaderCodec codec;

    /**
     * Each entry in this map represents a "stream". The key is the stream ID. This map is cleared
     * when the connection is closed. Each stream is removed from this map when the individual stream
     * is closed. Note that this map therefore correlates to the value for "MAX_CONCURRENT_STREAMS",
     * because only OPEN streams, or HALF_CLOSED streams are in here, once closed, they are removed
     * from this map.
     */
    private final Map<Integer, Http2Stream> streams = new ConcurrentHashMap<>();

    /**
     * According to the specification, all stream IDs sent by clients MUST be odd, and MUST
     * be bigger than previously received stream IDs. So we need to keep track of the highest
     * stream ID we've seen so far. This is helpful, because when we receive frames for streams
     * that have been previously closed, they are no longer in {@link #streams}, but according
     * to the spec only some of those frames are acceptable, others require us to throw an error.
     * So we need this highest stream ID information to be compliant.
     *
     * <p>When the connection is reset, this value is reset to 0.
     */
    private int highestStreamId = 0;

    /**
     * As each stream successfully closes, we keep track of its ID here. This is to be helpful
     * to the client. If we send a GO_AWAY frame from the connection, we include this ID, so
     * the client knows when it reconnects, which streams it should start replaying from. This
     * is a hint to the application. We reset the value to 0 when we reset the connection.
     */
    private int lastSuccessfulStreamId = 0;

    /**
     * Create a new instance.
     *
     * @param contextReuseManager The {@link ContextReuseManager} that manages this instance. Must not be null.
     * @param config The {@link WebServerConfig} for this running server instance.
     */
    public Http2ConnectionImpl(final ContextReuseManager contextReuseManager, final WebServerConfig config) {
        super(contextReuseManager, Settings.INITIAL_FRAME_SIZE);

        // Setup the server settings
        serverSettings.setMaxConcurrentStreams(config.maxConcurrentStreamsPerConnection());
        serverSettings.setMaxHeaderListSize(config.maxHeaderSize());
    }

    // =================================================================================================================
    // Connection Context Methods

    /**
     * Called by {@link DataHandler} when it realizes that a connection needs to be upgraded from
     * HTTP/1.1 to HTTP/2. When that happens, this method is called with the previous context, from which
     * we can fetch any data already present in the previous context.
     *
     * @param prev The previous context we need to copy data from. Cannot be null.
     */
    public void upgrade(final Http1ConnectionContext prev) {
        // NOTE: Called on the connection thread
        // Super does everything except for returning the prev context to the reuse manager
        super.upgrade(prev);
        contextReuseManager.returnHttp1ConnectionContext(prev);
    }

    @Override
    public void reset(final ByteChannel channel, final Runnable onCloseCallback) {
        // NOTE: Called on the connection thread
        super.reset(channel, onCloseCallback);
        this.state = State.START;
        this.clientSettings.resetToDefaults();
        this.streams.forEach((k, v) -> v.close());
        this.streams.clear();
        this.highestStreamId = 0;
        this.lastSuccessfulStreamId = 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void close() {
        // NOTE: May be called from any thread
        if (!isClosed()) {
            // Close each stream first, so they can send any frames on the socket first before
            // we close it all the way down.
            this.state = State.CLOSED;
            this.streams.forEach((k, v) -> v.close());
            this.streams.clear();

            // Closes the channel and everything
            super.close();

            // Return this instance to the pool
            this.contextReuseManager.returnHttp2ConnectionContext(this);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>This method never upgrades the connection, so the argument is ignored.
     */
    @Override
    public HandleResponse doHandle(final Consumer<HttpVersion> ignored) {
        // NOTE: Called on the connection thread
        try {
            // As long as we don't need more data, we will keep processing frames.
            var response = HandleResponse.DATA_STILL_TO_HANDLED;
            while (response == HandleResponse.DATA_STILL_TO_HANDLED) {
                switch (state) {
                    case START ->  handleStart();
                    case AWAITING_SETTINGS -> response = handleInitialSettings();
                    case OPEN -> response = handleOpen();
                    case CLOSED -> response = HandleResponse.CLOSE_CONNECTION;
                }
            }

            return response;
        } catch (Http2Exception e) {
            // SPEC: 5.4.1 Connection Error Handling
            // An endpoint that encounters a connection error SHOULD first send a GOAWAY frame (Section 6.8) with the
            // stream identifier of the last stream that it successfully received from its peer. The GOAWAY frame
            // includes an error code (Section 7) that indicates why the connection is terminating. After sending the
            // GOAWAY frame for an error condition, the endpoint MUST close the TCP connection.
            final OutputBuffer outputBuffer = contextReuseManager.checkoutOutputBuffer();
            GoAwayFrame.write(outputBuffer, e.getCode(), e.getStreamId(), lastSuccessfulStreamId);
            sendOutput(outputBuffer);
            close();
            return HandleResponse.CLOSE_CONNECTION;
        }
    }

    /**
     * Handles the START state. This simply sends the settings to the client.
     */
    private void handleStart() {
        // SPEC: 3.4 HTTP/2 Connection Preface
        // The server connection preface consists of a potentially empty SETTINGS frame (Section 6.5)
        // that MUST be the first frame the server sends in the HTTP/2 connection.
        final OutputBuffer outputBuffer = contextReuseManager.checkoutOutputBuffer();
        SettingsFrame.write(outputBuffer, serverSettings);
        sendOutput(outputBuffer);
        state = State.AWAITING_SETTINGS;
    }

    /**
     * The very first thing after the preface that the client sends to the server is a SETTINGS
     * frame. If they don't, it is an error. This stage in the state machine just makes sure
     * the next thing coming IS a SETTINGS frame. If it is, we're happy, and advance to the
     * {@link State#OPEN} state.
     *
     * @return TODO Write description
     */
    private HandleResponse handleInitialSettings() {
        // SPEC: 3.4 HTTP/2 Connection Preface
        // That is, the connection preface [from the client]... MUST be followed by a SETTINGS frame...
        // Clients and servers MUST treat an invalid connection preface as a connection error
        // (Section 5.4.1) of type PROTOCOL_ERROR.
        if (inputBuffer.available(Frame.FRAME_HEADER_SIZE)) {
            final var nextFrameType = inputBuffer.peekByte(3);
            if (nextFrameType != FrameType.SETTINGS.ordinal()) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, CONNECTION_STREAM_ID);
            }

            state = State.OPEN;
            return HandleResponse.DATA_STILL_TO_HANDLED;
        } else {
            return HandleResponse.ALL_DATA_HANDLED;
        }
    }

    /**
     * Called during the {@link State#OPEN} state. During this state, we're open to processing
     * all frames that come our way.
     *
     * @return true if we need to load more data to continue.
     */
    private HandleResponse handleOpen() {
        // We need to know we have *ALL* the frame data in our buffer before we start
        // processing the frame. The first three bytes of the frame tell us the length
        // of the payload. When added to the FRAME_HEADER_SIZE, we get the total number
        // of bytes for the frame. We then make sure we have ALL those bytes available
        // before we process anything.
        if (inputBuffer.available(Frame.FRAME_HEADER_SIZE)) {
            int length = inputBuffer.peek24BitInteger();
            // SPEC: 4.2 Frame Size
            // An endpoint MUST send an error code of FRAME_SIZE_ERROR if a frame exceeds the size
            // defined in SETTINGS_MAX_FRAME_SIZE... A frame size error in a frame that could alter
            // the state of the entire connection MUST be treated as a connection error
            // (Section 5.4.1); this includes any frame carrying a field block (Section 4.3)
            // (that is, HEADERS, PUSH_PROMISE, and CONTINUATION), a SETTINGS frame, and any frame
            // with a stream identifier of 0.
            if (length > serverSettings.getMaxFrameSize()) {
                final FrameType type = FrameType.valueOf(inputBuffer.peekByte(3));
                final int streamId = inputBuffer.peek31BitInteger(5);
                if (type == FrameType.HEADERS || type == FrameType.PUSH_PROMISE ||
                        type == FrameType.CONTINUATION || type == FrameType.SETTINGS || streamId == 0) {
                    throw new Http2Exception(Http2ErrorCode.CONNECT_ERROR, streamId);
                }
            }

            // Make sure we have enough data to handle the entire frame.
            if (inputBuffer.available(Frame.FRAME_HEADER_SIZE + length)) {
                final var type = FrameType.valueOf(inputBuffer.peekByte(3));
                switch (type) {
                    // SPEC: 6.1 DATA
                    case DATA -> handleData();
                    // SPEC: 6.2 HEADERS
                    case HEADERS -> handleHeaders();
                    // SPEC: 6.3 PRIORITY
                    case PRIORITY -> handlePriority();
                    // SPEC: 6.4 RST_STREAM
                    case RST_STREAM -> handleRstStream();
                    // SPEC: 6.5 SETTINGS
                    case SETTINGS -> handleSettings();
                    // SPEC: 6.6 PUSH_PROMISE
                    case PUSH_PROMISE -> handlePushPromise();
                    // SPEC: 6.7 PING
                    case PING -> handlePing();
                    // SPEC: 6.8 GO_AWAY
                    case GO_AWAY -> handleGoAway();
                    // SPEC: 6.9 WINDOW_UPDATE
                    case WINDOW_UPDATE -> handleWindowUpdate();
                    // SPEC: 6.10 CONTINUATION
                    case CONTINUATION -> handleContinuationFrame();
                    // SPEC: 4.1 Frame Format
                    // Implementations MUST ignore and discard frames of unknown types.
                    default -> skipUnknownFrame();
                }

                // We successfully handled the frame. So we don't need any more data.
                return HandleResponse.ALL_DATA_HANDLED;
            } else {
                // We need more data to get the entire frame loaded
                return HandleResponse.DATA_STILL_TO_HANDLED;
            }
        } else {
            // We need more data to get the frame header loaded
            return HandleResponse.DATA_STILL_TO_HANDLED;
        }
    }

    /**
     * Handles a DATA_FRAME.
     */
    private void handleData() {
        try {
            final var frame = new DataFrame();
            frame.parse2(inputBuffer);
            final var streamId = checkStream(frame.getStreamId());

            final var stream = streams.get(streamId);

            // The stream must exist. It can only NOT exist in one of two conditions, both of which
            // are evidence of a client breaking protocol. Either the stream has already been closed,
            // or the stream has not yet been opened.
            if (stream == null) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);

            }

            stream.handleDataFrame(frame);
        } catch (Http2Exception e) {
            onStreamException(e);
        }
    }

    /**
     * The headers frame indicates the start of a new stream. If an existing stream already exists,
     * it is a big problem! The spec doesn't say what should happen. So I'll start by being belligerent.
     */
    private void handleHeaders() {
        try {
            final var frame = HeadersFrame.parse(inputBuffer);
            final var streamId = checkStream(frame.getStreamId());

            // Please, don't try to send the same headers frame twice...
            // Haven't found this situation yet in the spec, but I bet it is there...
            if (streams.containsKey(streamId)) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
            }

            // SPEC: 5.1.2 Stream Concurrency
            // Endpoints MUST NOT exceed the limit set by their peer. An endpoint that receives a HEADERS frame that causes
            // its advertised concurrent stream limit to be exceeded MUST treat this as a stream error (Section 5.4.2) of
            // type PROTOCOL_ERROR or REFUSED_STREAM. The choice of error code determines whether the endpoint wishes to
            // enable automatic retry (see Section 8.7 for details).
            if (streams.size() > serverSettings.getMaxConcurrentStreams()) {
                throw new Http2Exception(Http2ErrorCode.REFUSED_STREAM, streamId);
            }

            // OK, so we have a new stream. That's great! Let's create a new Http2RequestContext to
            // handle it. When it is closed, we need to remove it from the map.
            final var stream = contextReuseManager.checkoutHttp2RequestContext();
            stream.init(this);
            highestStreamId = streamId;

            // Put it in the map
            streams.put(streamId, stream);

            // NOW initialize it. If we initialize first, the header might be the kind that results in the end of
            // the stream, embodying the whole request, which would cause the onClose to be called, so we want
            // to make sure we added things to the map first so it can then be removed
            stream.handleHeadersFrame(frame);
        } catch (Http2Exception e) {
            onStreamException(e);
        }
    }

    /**
     * Handles the PRIORITY frame. Even though we don't implement priority handling on this server,
     * we do need to handle these frames properly (and for the most part, throw exceptions if anything
     * is off, as per the spec).
     */
    private void handlePriority() {
        try {
            final var frame = PriorityFrame.parse(inputBuffer);
            final var streamId = checkStream(frame.getStreamId());

            // SPEC: 5.1 Stream States
            // An endpoint MUST NOT send frames other than PRIORITY on a closed stream
            //
            // So if we cannot find the stream, it is either because it has already been
            // closed (which is fine, we just ignore it), or because it hasn't been
            // created yet (which is not fine, and we'll throw an exception).
            final var stream = streams.get(streamId);
            if (stream == null) {
                if (streamId > highestStreamId) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
                } else {
                    // A closed stream can get a PRIORITY frame with no error.
                    return;
                }
            }

            stream.handlePriorityFrame(frame);
        } catch (Http2Exception e) {
            onStreamException(e);
        }
    }

    /**
     * Decodes and dispatches the RST_STREAM frame to the appropriate stream. This cannot
     * refer to the physical connection, only one of the logical connection streams.
     */
    private void handleRstStream() {
        final var frame = RstStreamFrame.parse(inputBuffer);
        final var streamId = checkStream(frame.getStreamId());

        // It is an error to receive an RST_STREAM frame for a stream that is already closed,
        // or hasn't been opened.
        final var stream = streams.get(streamId);
        if (stream == null) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        stream.handleRstStreamFrame(frame);
    }

    /**
     * Handles new settings coming from the client. Settings only apply to the physical connection context.
     */
    private void handleSettings() {
        // Read and save these off, because we only need to recreate the codec if these change
        final long formerMaxHeaderListSize = clientSettings.getMaxHeaderListSize();
        final long formerHeaderTableSize = clientSettings.getHeaderTableSize();

        // Merge the settings from the frame into the clientSettings we already have
        final var isAck = SettingsFrame.parseAndMerge(inputBuffer, clientSettings);
        if (!isAck) {
            // If the settings have changed, then recreate the codec
            if (formerMaxHeaderListSize != clientSettings.getMaxHeaderListSize() ||
                    formerHeaderTableSize != clientSettings.getHeaderTableSize()) {
                recreateCodec();
            }

            // Write the ACK frame to the client
            final OutputBuffer outputBuffer = contextReuseManager.checkoutOutputBuffer();
            SettingsFrame.writeAck(outputBuffer);
            sendOutput(outputBuffer);
        }
    }

    /**
     * Implements handling of PUSH_PROMISE frames. The client cannot send this to us because the
     * PUSH_PROMISE setting for the server is false.
     */
    private void handlePushPromise() {
        final var streamId = Frame.readAheadStreamId(inputBuffer);
        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
    }

    /**
     * Handles a "ping" frame. This is a connection level frame.
     */
    private void handlePing() {
        final var frame = new PingFrame();
        frame.parse2(inputBuffer);
        // SPEC: 6.7 PING
        // Receivers of a PING frame that does not include an ACK flag MUST send a PING frame with the ACK flag set in
        // response, with an identical frame payload.
        if (!frame.isAck()) {
            final OutputBuffer outputBuffer = contextReuseManager.checkoutOutputBuffer();
            frame.writeAck(outputBuffer);
            sendOutput(outputBuffer);
        }
    }

    /**
     * Handles the GO_AWAY frame.
     */
    private void handleGoAway() {
        // TODO The GOAWAY frame (type=0x07) is used to initiate shutdown of a connection or to signal serious error
        //      conditions. GOAWAY allows an endpoint to gracefully stop accepting new streams while still finishing
        //      processing of previously established streams. This enables administrative actions, like server
        //      maintenance.  SOOOOOO>>>> We should expose some notion of shutdown signal from the WebServer
        //      all the way through to this code so we can do a graceful shutdown experience.


    }

    private void handleWindowUpdate() {

    }

    private void handleContinuationFrame() {

    }

    /**
     * Skips the current frame.
     */
    private void skipUnknownFrame() {
        final var frameLength = inputBuffer.peek24BitInteger();
        inputBuffer.skip(frameLength + Frame.FRAME_HEADER_SIZE);
    }

    private int checkStream(int streamId) {
        // SPEC: 5.1.1 Stream Identifiers
        // Streams initiated by a client MUST use odd-numbered stream identifiers...
        // The identifier of a newly established stream MUST be numerically greater than all streams that the
        // initiating endpoint has opened or reserved. This governs streams that are opened using a HEADERS frame and
        // streams that are reserved using PUSH_PROMISE. An endpoint that receives an unexpected stream identifier MUST
        // respond with a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
        final var isOdd = (streamId & 1) != 1;
        if (isOdd || streamId < highestStreamId) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        return streamId;
    }

    private void onStreamException(Http2Exception exception) {
        // SPEC: 5.4.2 Stream Error Handling
        // An endpoint that detects a stream error sends a RST_STREAM frame (Section 6.4) that contains the stream
        // identifier of the stream where the error occurred. The RST_STREAM frame includes an error code that
        // indicates the type of error.
        final var streamId = exception.getStreamId();
        final var stream = streams.remove(streamId);
        if (stream != null) {
            contextReuseManager.returnHttp2RequestContext(stream);
        }

        // TODO We need to make sure that if the stream happens to be in the middle of handling
        //      on another thread and comes back from there, that it doesn't ever do anything.
        //      It may be that the Http2RequestContext is reused, and by the time the handler
        //      comes back, it finds that the Http2RequestContext has been reused and in a
        //      half-closed state ready for a response! We need to cancel that job and make
        //      sure it never comes back (back to the Future!!)
        final OutputBuffer outputBuffer = contextReuseManager.checkoutOutputBuffer();
        RstStreamFrame.write(outputBuffer, exception.getCode(), streamId);
        sendOutput(outputBuffer);
    }

    // =================================================================================================================
    // Http2Connection Methods

    /**
     * {@inheritDoc}
     */
    @Override
    public void close(int streamId) {
        // This method may be called from any thread, which is why "streams" is a concurrent collection.
        streams.remove(streamId);

        // TODO SPEC 6.8 GO_AWAY
        //      Endpoints SHOULD always send a GOAWAY frame before closing a connection so that the remote peer can
        //      know whether a stream has been partially processed or not. For example, if an HTTP client sends a POST
        //      at the same time that a server closes a connection, the client cannot know if the server started to
        //      process that POST request if the server does not send a GOAWAY frame to indicate what streams it might
        //      have acted on.


        // TODO I'm worried about GO_AWAY, it says something like "after sending go away for stream, you might still
        //      receive frames for a little while, and you should just ignore them". But I throw exceptions, and possibly
        //      connection ending exceptions. That's a big bug.
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Http2HeaderCodec getHeaderCodec() {
        return codec;
    }

    /*
        TODO: We need to implement flow control. We are supposed to implement it both on the
              connection level, and the stream level. We can say, for example, that each stream
              allows a maximum of 16K for the request. The client (is supposed to) respect that
              and make sure it doesn't send more than that. At the same time, on the connection
              level, we want to allow the client to send as much data as they like, but only
              maybe up to 16K at a time. In that case, we can send from the connection a
              WINDOW_UPDATE_FRAME indicating how much space they have left every time we read
              new bytes, and when we flip the buffer, we tell them they have a lot more space
              available. In this way, we can make sure that no one connection is going bananas
              and no one stream either. Of course, the client can ignore that and send whatever
              they want, but then they are just hurting themselves (their bytes are going to
              pile up at the networking level and they will end up frustrated, not us).
     */

    private void recreateCodec() {
        final var encoder = new Encoder((int) clientSettings.getMaxHeaderListSize());
        final var decoder = new Decoder(
                (int) clientSettings.getMaxHeaderListSize(),
                (int) clientSettings.getHeaderTableSize());

        codec = new Http2HeaderCodec(encoder, decoder);
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
}
