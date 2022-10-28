package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.HttpVersion;
import com.hedera.hashgraph.web.WebServerConfig;
import com.hedera.hashgraph.web.impl.ChannelManager;
import com.hedera.hashgraph.web.impl.http.Http1ConnectionContext;
import com.hedera.hashgraph.web.impl.http2.frames.*;
import com.hedera.hashgraph.web.impl.session.ConnectionContext;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;
import com.hedera.hashgraph.web.impl.session.HandleResponse;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import com.twitter.hpack.Decoder;
import com.twitter.hpack.Encoder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.ByteChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * An implementation of {@link ConnectionContext} representing the physical connection for an HTTP/2
 * request. When this context is closed, the corresponding channel is also closed.
 *
 * <p>The HTTP/2 input is a set of "frames", with each frame either belonging to the connection itself,
 * or to one or more "streams". Each "stream" is represented by the {@link Http2Stream}. Frames
 * for different streams, or for the connection itself, are interleaved on the wire. The context needs
 * to decode the frames, determine the stream for the frame, create if necessary an {@link Http2Stream},
 * and delegate to it.
 */
public final class Http2ConnectionImpl extends ConnectionContext implements Http2Connection {
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
         * When a HEADER frame is processed that does not have the END_HEADERS flag set,
         * then we enter this CONTINUATION state, and we stay in this state until we see
         * a CONTINUATION frame with END_HEADERS set to true. As per the specification
         * (Section 4.3) there can be no other frames interleaved between a HEADERS frame
         * and the final CONTINUATION frame.
         */
        CONTINUATION,
        /**
         * We enter this state when we are closing down the connection gracefully. In this state
         * we will continue to process any in-flight streams, but accept no new frames.
         */
        CLOSING,
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
    private final SettingsFrame serverSettings = new SettingsFrame();

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
     * <p>In addition, when we encounter a HEADERS frame with END_HEADERS set to "false" we
     * reference this ID to make sure the next frames are CONTINUATION frames for the same
     * stream, until we finally get to the last CONTINUATION frame with END_HEADERS set
     * to "true".
     *
     * <p>When the connection is reset, this value is reset to 0.
     */
    private int highestStreamId = 0;

    /**
     * This setting is not explicitly defined as part of the specification, but it used to detect
     * and terminate connections with misbehaving or malicious clients. For every infraction
     * incurred by the client, including those that result in a GOAWAY and eventual termination
     * of the connection, we increase the penalty count. If we are *NOT* in the {@link State#CLOSING
     * state, then we reduce the penalty count by 1 whenever a frame is correctly handled. When the
     * penalty count crosses some configured threshold ({@link #patienceThreshold}) the connection
     * is aggressively terminated.
     */
    private int penaltyCount = 0;

    /**
     * The maximum per-connection "SETTINGS_HEADER_TABLE_SIZE" that we will use.
     */
    private final int maxHeaderTableSize;

    /**
     * At this threshold, we've run out of patience with penalties ({@link #penaltyCount}) and
     * will aggressively terminate the connection.
     */
    private final int patienceThreshold;

    /**
     * Keeps track of the "credit", or number of bytes we can send to the client before we need
     * to receive "more credit" via a WINDOW_UPDATE frame.
     */
    private final AtomicInteger clientFlowControlCredits = new AtomicInteger(1 << 16);

    private final ReentrantLock flowControlLock = new ReentrantLock();

    /**
     * A {@link ContinuationFrame} that can be reused for parsing continuation frame data
     */
    private final ContinuationFrame continuationFrame = new ContinuationFrame();

    /**
     * A {@link DataFrame} that can be reused for parsing data frame data
     */
    private final DataFrame dataFrame = new DataFrame();

    /**
     * A {@link GoAwayFrame} that can be reused for parsing GOAWAY frame data
     */
    private final GoAwayFrame goAwayFrame = new GoAwayFrame();

    /**
     * A {@link HeadersFrame} that can be reused for parsing header frame data
     */
    private final HeadersFrame headersFrame = new HeadersFrame();

    /**
     * A {@link PingFrame} that can be reused for parsing ping frame data
     */
    private final PingFrame pingFrame = new PingFrame();

    /**
     * A {@link PriorityFrame} that can be reused for parsing Priority frame data
     */
    private final PriorityFrame priorityFrame = new PriorityFrame();

    /**
     * A {@link SettingsFrame} that can be reused for parsing SETTING frame data
     */
    private final SettingsFrame settingsFrame = new SettingsFrame();

    /**
     * A {@link WindowUpdateFrame} that can be reused for parsing window update frame data
     */
    private final WindowUpdateFrame windowUpdateFrame = new WindowUpdateFrame();

    /**
     * A {@link RstStreamFrame} that can be reused for parsing reset frame data
     */
    private final RstStreamFrame rstStreamFrame = new RstStreamFrame();

    /**
     * Create a new instance.
     *
     * @param contextReuseManager The {@link ContextReuseManager} that manages this instance. Must not be null.
     * @param config The {@link WebServerConfig} for this running server instance.
     */
    public Http2ConnectionImpl(final ContextReuseManager contextReuseManager, final WebServerConfig config) {
        super(contextReuseManager, Settings.INITIAL_FRAME_SIZE + Frame.FRAME_HEADER_SIZE);

        // Set up the server settings
        serverSettings.setMaxConcurrentStreams(config.maxConcurrentStreamsPerConnection());
        serverSettings.setMaxHeaderListSize(config.maxHeaderSize());

        // And other settings
        maxHeaderTableSize = config.maxHeaderTableSize();
        patienceThreshold = config.patienceThreshold();

        // Create the codec. This probably is wasted effort because the first frame from any client
        // involves a settings frame, but we should not let this be null.
        recreateCodec();
    }

    // =================================================================================================================
    // Connection Context Methods

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void reset(final ByteChannel channel, final BiConsumer<Boolean, ConnectionContext> onCloseCallback) {
        // NOTE: Called on the connection thread
        super.reset(channel, onCloseCallback);
        this.state = State.START;
        this.clientSettings.resetToDefaults();
        // TODO Hmmmm.
        this.streams.forEach((k, v) -> v.terminate());
        this.streams.clear();
        this.highestStreamId = 0;
        this.penaltyCount = 0;
        this.clientFlowControlCredits.set((1 << 16) - 1);

        // NOTE: "codec" is not reset because the first frame from the client is a Settings frame
        //       which will cause us to rebuild the codec if necessary, otherwise, we can just
        //       leave it as-is.
    }

    /**
     * Called by {@link ChannelManager} when it realizes that a connection needs to be upgraded from
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

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void close() {
        // NOTE: May be called from any thread
        if (!isClosed()) {
            this.state = State.CLOSED;
            this.streams.forEach((k, v) -> v.terminate());
            this.streams.clear();
            super.close();
            this.contextReuseManager.returnHttp2ConnectionContext(this);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>This method never upgrades the connection, so the argument is ignored.
     */
    @Override
    public void doHandle(final Consumer<HttpVersion> ignored) {
        // NOTE: Called on the connection thread
        try {
            // As long as we don't need more data, we will keep processing frames.
            var response = HandleResponse.DATA_STILL_TO_HANDLED;
            while (response == HandleResponse.DATA_STILL_TO_HANDLED) {
                switch (state) {
                    case START ->  handleStart();
                    case AWAITING_SETTINGS -> response = handleInitialSettings();
                    case OPEN, CLOSING -> response = handleOpenOrClosing();
                    case CONTINUATION -> response = handleContinuation();
                    case CLOSED -> response = HandleResponse.CLOSE_CONNECTION;
                }

                // We have successfully handled a frame, so reduce the penalty count,
                // unless we're CLOSING or CLOSED.
                if (penaltyCount > 0 && state != State.CLOSING && state != State.CLOSED) {
                    penaltyCount--;
                }
            }
        } catch (Http2Exception e) {
            // SPEC: 5.4.1 Connection Error Handling
            // An endpoint that encounters a connection error SHOULD first send a GOAWAY frame (Section 6.8) with the
            // stream identifier of the last stream that it successfully received from its peer. The GOAWAY frame
            // includes an error code (Section 7) that indicates why the connection is terminating. After sending the
            // GOAWAY frame for an error condition, the endpoint MUST close the TCP connection.

            // Create the GOAWAY frame and send it to the client.
            final OutputBuffer outputBuffer = contextReuseManager.checkoutOutputBuffer();
            goAwayFrame.setLastStreamId(highestStreamId)
                    .setErrorCode(e.getCode())
                    .setStreamId(0)
                    .write(outputBuffer);
            sendOutput(outputBuffer);

            // We enter the CLOSED state, which will NOT allow the processing of any additional frames,
            // or graceful shutdown of the connection. Because we use NIO, we have to keep the channel
            // open long enough to flush whatever we have in our output buffers out to the client
            // (or they'd never get the GOAWAY frame!), but otherwise the connection should be torn down.
            close();
        } catch (BadClientException e) {
            // The client was bad, wracked up too many penalties, so we're going to close it down hard.
            close();
        }
    }

    // =================================================================================================================
    // Implementation of the various lifecycle methods

    /**
     * Handles the START state. This simply sends the settings to the client.
     */
    private void handleStart() {
        // SPEC: 3.4 HTTP/2 Connection Preface
        // The server connection preface consists of a potentially empty SETTINGS frame (Section 6.5)
        // that MUST be the first frame the server sends in the HTTP/2 connection.
        final OutputBuffer outputBuffer = contextReuseManager.checkoutOutputBuffer();
        serverSettings.write(outputBuffer);
        sendOutput(outputBuffer);
        state = State.AWAITING_SETTINGS;
    }

    /**
     * The very first thing after the preface that the client sends to the server is a SETTINGS
     * frame. If they don't, it is an error. This stage in the state machine just makes sure
     * the next thing coming IS a SETTINGS frame. If it is, we're happy, and advance to the
     * {@link State#OPEN} state.
     *
     * @return A {@link HandleResponse} to indicate whether we have read all the data we can,
     *         and that we need more data, or whether this is more data remaining to be
     *         processed.
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
     * @return A {@link HandleResponse} to indicate whether we have read all the data we can,
     *         and that we need more data, or whether this is more data remaining to be
     *         processed.
     */
    private HandleResponse handleOpenOrClosing() {
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
                // NOTE: It is up to the implementation to decide whether to throw a connection error
                // or a stream error in this case. For now we throw a connection error.
                final int streamId = inputBuffer.peek31BitInteger(5);
                throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, streamId);
            }

            // Make sure we have enough data to handle the entire frame.
            if (inputBuffer.available(Frame.FRAME_HEADER_SIZE + length)) {
                final var type = FrameType.valueOf(inputBuffer.peekByte(3));
                if (type == null) {
                    skipUnknownFrame();
                } else {
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
                }

                // We successfully handled the frame. There may be more data to process,
                // so we're ready to go for another lap.
                return HandleResponse.DATA_STILL_TO_HANDLED;
            } else {
                // We need more data to get the entire frame loaded
                return HandleResponse.ALL_DATA_HANDLED;
            }
        } else {
            // We need more data to get the frame header loaded
            return HandleResponse.ALL_DATA_HANDLED;
        }
    }

    /**
     * Called to handle the {@link State#CONTINUATION} state. When we encounter a HEADERS frame with
     * END_HEADERS set to false, then <b>every subsequent frame MUST be a CONTINUATION frame for the
     * same stream</b>, until we encounter a CONTINUATION frame with END_HEADERS set to true. Then
     * we can go back to the {@link State#OPEN} state.
     *
     * @return A {@link HandleResponse} to indicate whether we have read all the data we can,
     *         and that we need more data, or whether this is more data remaining to be
     *         processed.
     */
    private HandleResponse handleContinuation() {
        // SPEC: 4.3 Field Section Compression and Decompression
        // Each field block is processed as a discrete unit. Field blocks MUST be transmitted as a contiguous sequence
        // of frames, with no interleaved frames of any other type or from any other stream. The last frame in a
        // sequence of HEADERS or CONTINUATION frames has the END_HEADERS flag set. The last frame in a sequence of
        // PUSH_PROMISE or CONTINUATION frames has the END_HEADERS flag set. This allows a field block to be logically
        // equivalent to a single frame.

        // We need to know we have the frame header data available, so we can validate the stream ID
        // and the frame type is CONTINUATION. Then we can throw or delegate.
        if (inputBuffer.available(Frame.FRAME_HEADER_SIZE)) {
            final var ord = inputBuffer.peekByte(3);
            final var type = FrameType.valueOf(ord);
            final var streamId = inputBuffer.peek31BitInteger(5);
            if (type != FrameType.CONTINUATION || streamId != highestStreamId) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
            }

            // All is well, delegate handleOpenOrClosing to do the normal processing.
            return handleOpenOrClosing();
        }
        // We need more data
        return HandleResponse.ALL_DATA_HANDLED;
    }

    // =================================================================================================================
    // Handlers for all different types of frames

    /**
     * Handles a DATA_FRAME.
     */
    private void handleData() {
        // Parse the frame (syntactically invalid frames will fail this part)
        dataFrame.parse2(inputBuffer);

        // Get the stream id and make sure it is odd
        final var streamId = checkStreamIsOdd(dataFrame.getStreamId());

        // No matter what happens with this Data frame, report back that we have space
        // for more data frames.
        sendWindowUpdateFrame(dataFrame.getDataLength());

        // SPEC 6.8 GOAWAY
        // After sending a GOAWAY frame, the sender can discard frames for streams initiated by the receiver with
        // identifiers higher than the identified last stream... [but] DATA frames MUST be counted toward the
        // connection flow-control window.
        if (state == State.CLOSING && streamId < highestStreamId) {
            return;
        }

        // The stream must exist. It can only NOT exist in one of two conditions, both of which
        // are evidence of a client breaking protocol. Either the stream has already been closed,
        // or the stream has not yet been opened.
        final var stream = streams.get(streamId);
        if (stream == null) {
            if (streamId <= highestStreamId) {
                throw new Http2Exception(Http2ErrorCode.STREAM_CLOSED, streamId);
            } else {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
            }
        }

        // Delegate to the stream to handle the data frame. If the stream throws an Http2Exception,
        // then terminate the stream (not the connection).
        stream.handleDataFrame(dataFrame);
    }

    /**
     * The headers frame indicates the start of a new stream. If an existing stream already exists,
     * it is a big problem! The spec doesn't say what should happen. So I'll start by being belligerent.
     */
    private void handleHeaders() {
        // Parse the header frame. This call verifies it is syntactically correct
        headersFrame.parse2(inputBuffer);

        // Check the stream ID to make sure it is ODD
        final var streamId = checkStreamIsOdd(headersFrame.getStreamId());

        // SPEC: 5.1.1 Stream Identifiers
        // A server that is unable to establish a new stream identifier can send a GOAWAY frame so that the client is
        // forced to open a new connection for new streams.
        //
        // NOTE: This isn't a problem for us, because the streamId is a 31-bit int (unsigned) and therefore
        //       the highest value it can be is 2^31, which is fine. Any streams created after this won't
        //       have a higher value available, so they will necessarily have to close things down and start
        //       a new connection, or send us a bogus stream ID we will close on (down below).

        // SPEC 6.8 GOAWAY
        // After sending a GOAWAY frame, the sender can discard frames for streams initiated by the receiver with
        // identifiers higher than the identified last stream. However, any frames that alter connection state cannot
        // be completely ignored. For instance, HEADERS, PUSH_PROMISE, and CONTINUATION frames MUST be minimally
        // processed to ensure that the state maintained for field section compression is consistent (see Section 4.3)
        // similarly, DATA frames MUST be counted toward the connection flow-control window. Failure to process these
        // frames can cause flow control or field section compression state to become unsynchronized.
        if (state == State.CLOSING) {
            // I have to process this frame's data to keep my compression state in sync. But otherwise I can
            // refuse the connection.
            try {
                codec.decode(null, new ByteArrayInputStream(headersFrame.getFieldBlockFragment()));
                // TODO Not sure if REFUSED_STREAM is right here...
                throw new Http2Exception(Http2ErrorCode.REFUSED_STREAM, streamId);
            } catch (IOException exception) {
                // This shouldn't be possible. If it happens, terminate things.
                exception.printStackTrace();
                close();
                return;
            }
        }

        var stream = streams.get(streamId);
        if (stream == null) {
            // SPEC: 5.1.1 Stream Identifiers
            // Streams initiated by a client MUST use odd-numbered stream identifiers...
            // The identifier of a newly established stream MUST be numerically greater than all streams that the
            // initiating endpoint has opened or reserved. This governs streams that are opened using a HEADERS frame and
            // streams that are reserved using PUSH_PROMISE. An endpoint that receives an unexpected stream identifier MUST
            // respond with a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
            if (streamId <= highestStreamId) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
            }

            // This should absolutely not be possible because the stream ID has to be strictly
            // increasing, as covered by the above code
            assert !streams.containsKey(streamId);

            // SPEC: 5.1.2 Stream Concurrency
            // Endpoints MUST NOT exceed the limit set by their peer. An endpoint that receives a HEADERS frame that causes
            // its advertised concurrent stream limit to be exceeded MUST treat this as a stream error (Section 5.4.2) of
            // type PROTOCOL_ERROR or REFUSED_STREAM. The choice of error code determines whether the endpoint wishes to
            // enable automatic retry (see Section 8.7 for details).
            if (streams.size() >= serverSettings.getMaxConcurrentStreams()) {
                // TODO Do I need to process the header data to keep my compression states in sync?? Almost certainly
                throw new Http2Exception(Http2ErrorCode.REFUSED_STREAM, streamId);
            }

            // OK, so we have a new stream. That's great! Let's create a new Http2RequestContext to
            // handle it. When it is closed, we need to remove it from the map.
            stream = contextReuseManager.checkoutHttp2RequestContext();
            stream.init(this, streamId, clientSettings);
            highestStreamId = streamId;

            // Put it in the map
            streams.put(streamId, stream);

            // SPEC: 6.2
            // A HEADERS frame without the END_HEADERS flag set MUST be followed by a CONTINUATION frame for the same
            // stream. A receiver MUST treat the receipt of any other type of frame or a frame on a different stream as
            // a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
            if (!headersFrame.isEndHeaders()) {
                this.state = State.CONTINUATION;
            }
        }

        // Delegate to the stream to handle the header frame.
        stream.handleHeadersFrame(headersFrame);
    }

    /**
     * Handles the PRIORITY frame. Even though we don't implement priority handling on this server,
     * we do need to handle these frames properly (and for the most part, throw exceptions if anything
     * is off, as per the spec).
     */
    private void handlePriority() {
        // Parse the frame, performing syntactic validation
        priorityFrame.parse2(inputBuffer);
        // Check the stream ID, which must be ODD
        final var streamId = checkStreamIsOdd(priorityFrame.getStreamId());

        // SPEC 6.8 GOAWAY
        // Once the GOAWAY is sent, the sender will ignore frames sent on streams initiated by the receiver if the
        // stream has an identifier higher than the included last stream identifier
        if (state == State.CLOSING && streamId > highestStreamId) {
            return;
        }

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

        // Delegate to the stream to handle the frame
        stream.handlePriorityFrame(priorityFrame);
    }

    /**
     * Decodes and dispatches the RST_STREAM frame to the appropriate stream. This cannot
     * refer to the physical connection, only one of the logical connection streams.
     */
    private void handleRstStream() {
        rstStreamFrame.parse2(inputBuffer);
        final var streamId = checkStreamIsOdd(rstStreamFrame.getStreamId());

        // SPEC 6.8 GOAWAY
        // Once the GOAWAY is sent, the sender will ignore frames sent on streams initiated by the receiver if the
        // stream has an identifier higher than the included last stream identifier
        if (state == State.CLOSING && streamId > highestStreamId) {
            return;
        }

        // It is an error to receive an RST_STREAM frame for a stream that is already closed,
        // or hasn't been opened.
        final var stream = streams.get(streamId);
        if (stream == null) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        // Delegate to the stream to handle
        stream.handleRstStreamFrame(rstStreamFrame);
    }

    /**
     * Handles new settings coming from the client. Settings only apply to the physical connection context.
     */
    private void handleSettings() {
        // Read and save these off, because we only need to recreate the codec if these change
        final long formerMaxHeaderListSize = clientSettings.getMaxHeaderListSize();
        final long formerHeaderTableSize = clientSettings.getHeaderTableSize();

        // Merge the settings from the frame into the clientSettings we already have
        settingsFrame.parse2(inputBuffer);
        if (!settingsFrame.isAck()) {
            // SPEC: 4.3.1 Compression State
            // The HPACK encoder at that endpoint can set the dynamic table to any size up to the maximum value set by
            // the decoder
            //
            // This means that we don't need to allow the full range of values, and in fact if we did, we could
            // be open to a DOS where the client asks for a really, really, big size. So we will cap the maximum
            // header table size.
            final var effectiveHeaderTableSize = Math.min(settingsFrame.getHeaderTableSize(), maxHeaderTableSize);

            // If the settings have changed, then recreate the codec
            if (formerMaxHeaderListSize != settingsFrame.getMaxHeaderListSize() ||
                    formerHeaderTableSize != effectiveHeaderTableSize) {
                recreateCodec();
            }

            // Update the flow control credits for the connection
            flowControlLock.lock();
            try {
                final var diff = settingsFrame.getInitialWindowSize() - clientSettings.getInitialWindowSize();
                clientFlowControlCredits.addAndGet((int) diff);
                final var itr = streams.values().iterator();
                while (itr.hasNext()) {
                    final var stream = itr.next();
                    stream.updateFlowControlCredits(diff);
                }
            } finally {
                flowControlLock.unlock();
            }

            settingsFrame.mergeInto(clientSettings);

            // Write the ACK frame to the client
            final OutputBuffer outputBuffer = contextReuseManager.checkoutOutputBuffer();
            settingsFrame.writeAck(outputBuffer);
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
        pingFrame.parse2(inputBuffer);
        // SPEC: 6.7 PING
        // Receivers of a PING frame that does not include an ACK flag MUST send a PING frame with the ACK flag set in
        // response, with an identical frame payload.
        if (!pingFrame.isAck()) {
            final OutputBuffer outputBuffer = contextReuseManager.checkoutOutputBuffer();
            pingFrame.writeAck(outputBuffer);
            sendOutput(outputBuffer);
        }
    }

    /**
     * Handles the GO_AWAY frame.
     */
    private void handleGoAway() {
        // Parse the GOAWAY frame and verify it syntactically
        goAwayFrame.parse2(inputBuffer);

        // We don't really care why the client told us to go away, we'll just close things down.
        this.state = State.CLOSED;
    }

    /**
     * Handles the WINDOW_UPDATE frame, coming from the client. We need to respect the client's
     * windows when it comes to sending bytes.
     *
     * TODO I'm not sure how to do this. We create buffers from various random threads, and we
     *      submit them to a queue to be sent. Some generic code then gets involved. I need
     *      to somehow get involved in that process so I can only send frames when there
     *      is enough space on the client.
     */
    private void handleWindowUpdate() {
        // SPEC 6.8 GOAWAY
        // Once the GOAWAY is sent, the sender will ignore frames sent on streams initiated by the receiver if the
        // stream has an identifier higher than the included last stream identifier

        flowControlLock.lock();
        try {
            // TODO ALL WRONG
            windowUpdateFrame.parse2(inputBuffer);
            if (windowUpdateFrame.getStreamId() == 0) {
                // SPEC 6.9.1
                // A sender MUST NOT allow a flow-control window to exceed 231-1 octets. If a sender receives a WINDOW_UPDATE
                // that causes a flow-control window to exceed this maximum, it MUST terminate either the stream or the
                // connection, as appropriate. For streams, the sender sends a RST_STREAM with an error code of
                // FLOW_CONTROL_ERROR; for the connection, a GOAWAY frame with an error code of FLOW_CONTROL_ERROR is sent.
                final var wouldOverflow = (long) clientFlowControlCredits.get() + windowUpdateFrame.getWindowSizeIncrement() >= (1L << 31);
                if (wouldOverflow) {
                    throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR, 0);
                }

                clientFlowControlCredits.addAndGet(windowUpdateFrame.getWindowSizeIncrement());
            } else {
                // It is an error to receive an RST_STREAM frame for a stream that is already closed,
                // or hasn't been opened.
                final var streamId = checkStreamIsOdd(windowUpdateFrame.getStreamId());
                final var stream = streams.get(streamId);
                if (stream == null) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
                }

                stream.handleWindowUpdateFrame(windowUpdateFrame);
            }

            // TODO If we ignore these frames, then it is possible that when we're trying to flush
            //      buffers to the client we are unable to send everything. So I think we should
            //      continue to process these.
        } finally {
            flowControlLock.unlock();
        }
    }

    /**
     * Handles the CONTINUATION frame
     */
    private void handleContinuationFrame() {
        // Parse the CONTINUATION frame, checking semantics
        continuationFrame.parse2(inputBuffer);

        // SPEC 6.8 GOAWAY
        // After sending a GOAWAY frame, the sender can discard frames for streams initiated by the receiver with
        // identifiers higher than the identified last stream. However, any frames that alter connection state cannot
        // be completely ignored. For instance, HEADERS, PUSH_PROMISE, and CONTINUATION frames MUST be minimally
        // processed to ensure that the state maintained for field section compression is consistent (see Section 4.3)
        // similarly, DATA frames MUST be counted toward the connection flow-control window. Failure to process these
        // frames can cause flow control or field section compression state to become unsynchronized.
        final var streamId = checkStreamIsOdd(continuationFrame.getStreamId());
        if (state == State.CLOSING && streamId != highestStreamId) {
            // I have to process this frame's data to keep my compression state in sync. But otherwise I can
            // refuse the connection.
            try {
                codec.decode(null, new ByteArrayInputStream(continuationFrame.getFieldBlockFragment()));
                return;
            } catch (IOException exception) {
                // This shouldn't be possible. If it happens, terminate things.
                exception.printStackTrace();
                close();
                return;
            }
        }

        // If we got to the end of this stream of continuation frames, then we can
        // reset our state to OPEN
        if (continuationFrame.isEndHeaders()) {
            state = State.OPEN;
        }

        // The stream must exist. It can only NOT exist in one of two conditions, both of which
        // are evidence of a client breaking protocol. Either the stream has already been closed,
        // or the stream has not yet been opened.
        final var stream = streams.get(streamId);
        if (stream == null) {
            if (streamId <= highestStreamId) {
                throw new Http2Exception(Http2ErrorCode.STREAM_CLOSED, streamId);
            } else {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
            }
        }

        // Delegate to the stream to handle the continuation frame. If the stream throws an Http2Exception,
        // then terminate the stream (not the connection).
        stream.handleContinuationFrame(continuationFrame);
    }

    /**
     * Skips the current frame.
     */
    private void skipUnknownFrame() {
        final var frameLength = inputBuffer.peek24BitInteger();
        inputBuffer.skip(frameLength + Frame.FRAME_HEADER_SIZE);
    }

    /**
     * Simple utility to check whether the stream ID is odd (it must be, if it originated
     * from the client!)
     *
     * @param streamId The stream ID to check
     * @return The stream ID
     * @throws Http2Exception of type PROTOCOL_ERROR if the stream ID not odd.
     */
    private int checkStreamIsOdd(int streamId) {
        // SPEC: 5.1.1 Stream Identifiers
        // Streams initiated by a client MUST use odd-numbered stream identifiers...
        final var isOdd = (streamId & 1) == 1;
        if (!isOdd) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        return streamId;
    }

    // =================================================================================================================
    // Http2Connection Methods

    /**
     * {@inheritDoc}
     */
    @Override
    public void onClose(int streamId) {
//        streams.remove(streamId);
    }

    @Override
    public void onBadClient(int streamId) {
        if (++penaltyCount > patienceThreshold) {
            // We've lost patience. Shut it down.
            // TODO Don't throw an exception, actually terminate things
            throw new BadClientException();
        }
    }

    @Override
    public void onTerminate(int streamId) {
        // This method may be called from any thread, which is why "streams" is a concurrent collection.
        streams.remove(streamId);
    }

    @Override
    public OutputBuffer getOutputBuffer() {
        return contextReuseManager.checkoutOutputBuffer();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Http2HeaderCodec getHeaderCodec() {
        return codec;
    }

    @Override
    public ReentrantLock getFlowControlLock() {
        return flowControlLock;
    }

    @Override
    public AtomicInteger getFlowControlCredits() {
        return clientFlowControlCredits;
    }

    /**
     * Recreates the codec based on the current client settings.
     */
    private void recreateCodec() {
        final var effectiveHeaderTableSize = Math.min(clientSettings.getHeaderTableSize(), maxHeaderTableSize);
        final var encoder = new Encoder((int) clientSettings.getMaxHeaderListSize());
        final var decoder = new Decoder(
                256,
                (int) effectiveHeaderTableSize);

        codec = new Http2HeaderCodec(encoder, decoder);
    }

    /**
     * Sends a WINDOW_UPDATE frame to the client, giving it feedback on additional bytes it can send
     * to the server. We try to always maintain the same available window, so as we receive data
     * we tell the client about the bytes we received so they can send more bytes.
     *
     * @param increment The amount to increase the window by
     */
    private void sendWindowUpdateFrame(int increment) {
        final OutputBuffer outputBuffer = contextReuseManager.checkoutOutputBuffer();
        windowUpdateFrame.setStreamId(0)
                .setWindowSizeIncrement(increment)
                .write(outputBuffer);
        sendOutput(outputBuffer);
    }
}
