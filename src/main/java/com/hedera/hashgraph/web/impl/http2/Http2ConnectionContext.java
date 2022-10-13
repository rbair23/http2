package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.HttpVersion;
import com.hedera.hashgraph.web.impl.*;
import com.hedera.hashgraph.web.impl.http.Http1ConnectionContext;
import com.hedera.hashgraph.web.impl.http2.frames.*;
import com.hedera.hashgraph.web.impl.session.ConnectionContext;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;
import com.hedera.hashgraph.web.impl.util.InputBuffer;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * An implementation of {@link ConnectionContext} representing the physical connection for an HTTP/2.0
 * request. When this context is closed, the corresponding channel is also closed.
 * <p>
 * The HTTP2 input is a set of "frames", with each frame either belonging to the connection itself,
 * or to one or more "streams". Each "stream" is a logical HTTP connection, represented by the
 * {@link Http2RequestContext}. Frames for different streams, or for the connection itself, are interleaved
 * on the wire. The context needs to decode the frames, determine the stream for the frame, create
 * if necessary a request context, and delegate to it.
 */
public final class Http2ConnectionContext extends ConnectionContext {
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
     * As with all {@link ConnectionContext} implementations, this implementation contains a state
     * machine, such that as bytes are read from the input, we behave differently depending on which
     * state we're in. This enum defines the states.
     */
    private enum State { START, AWAITING_SETTINGS, AWAITING_FRAME, CLOSED };

    /**
     * The current state of this context.
     */
    private State state = State.START;

    /**
     * The settings that the server sets to the client. The client must respect these settings.
     * These settings, once set, are never altered during the run of the server. (Theoretically
     * they could be, but we don't support that at this time).
     */
    private final Settings serverSettings = new Settings();

    /**
     * The settings sent from the client to the server. We start off with a default set of settings,
     * and when the client connects, it must send its settings. Those settings sent by the client
     * will override the defaults here. When the context is closed, these settings are reset to their
     * default values.
     */
    private final Settings clientSettings = new Settings();

    /**
     * Each entry in this map represents a "stream". The key is the stream ID. This map is cleared
     * when the context is closed. Each stream is removed from this map when the individual stream
     * is closed.
     */
    private Map<Integer, Http2RequestContext> streams = new HashMap<>();

    /**
     * Create a new instance.
     *
     * @param contextReuseManager The {@link ContextReuseManager} that manages this instance. Must not be null.
     */
    public Http2ConnectionContext(final ContextReuseManager contextReuseManager) {
        super(contextReuseManager, Settings.INITIAL_FRAME_SIZE);

        // TODO this configuration value really should come from the web server configs
        serverSettings.setMaxConcurrentStreams(100);
    }

    /**
     * Called by {@link IncomingDataHandler} when it realizes that a connection needs to be upgraded from
     * HTTP/1.1 to HTTP/2.0. When that happens, this method is called with the previous context, from which
     * we can fetch any data already present in the previous context.
     *
     * @param prev The previous context we need to copy data from
     */
    public void upgrade(Http1ConnectionContext prev) {
        // TODO copy over the state from the current connection context
        this.channel = prev.getChannel();
        this.inputBuffer.init(prev.getInputBuffer());
        contextReuseManager.returnHttp1ConnectionContext(prev);

    }

    @Override
    public void reset(SocketChannel channel, Runnable onCloseCallback) {
        super.reset(channel, onCloseCallback);
        clientSettings.resetToDefaults();
        contextReuseManager.returnHttp2ConnectionContext(this);
        // Note: We don't need to reset the server settings. They never change.
    }

    @Override
    public void close() {
        // Close each stream first, so they can send any frames on the socket first before
        // we close it all the way down.
        this.streams.forEach((k, v) -> v.close());
        this.streams.clear();
        super.close();
    }

    // NOTE: Once in this context, we never perform an upgrade
    @Override
    public boolean handle(Consumer<HttpVersion> ignored) {
        try {
            var needMoreData = false;
            while (!needMoreData) {
                switch (state) {
                    // At the very start, the first thing we do is send the SERVER settings to the client.
                    // Then, we transition to awaiting settings from the client.
                    case START ->  {
                        outputBuffer.reset();
                        SettingsFrame.write(outputBuffer, serverSettings);
                        outputBuffer.sendContentsToChannel(channel);
                        state = State.AWAITING_SETTINGS;
                    }
                    // SPEC: 3.4 HTTP/2 Connection Preface
                    // That is, the connection preface ... MUST be followed by a SETTINGS frame...
                    // Clients and servers MUST treat an invalid connection preface as a connection error
                    // (Section 5.4.1) of type PROTOCOL_ERROR.
                    case AWAITING_SETTINGS -> {
                        if (inputBuffer.available(FRAME_HEADER_SIZE)) {
                            final var nextFrameType = inputBuffer.peekByte(3);
                            if (nextFrameType != FrameType.SETTINGS.ordinal()) {
                                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, CONNECTION_STREAM_ID);
                            }

                            state = State.AWAITING_FRAME;
                        } else {
                            needMoreData = true;
                        }
                    }
                    // Now that the connection is open and functioning, we will await any kind of frame
                    // (even another settings frame). If the frame happens to be for the connection,
                    // we handle it strait away. If the frame is for a stream, we delegate it.
                    case AWAITING_FRAME -> {
                        // We need to know we have *ALL* the frame data in our buffer before we start
                        // processing the frame. The first three bytes of the frame tell us the length
                        // of the payload. When added to the FRAME_HEADER_SIZE, we get the total number
                        // of bytes for the frame. We then make sure we have ALL those bytes available
                        // before we process anything.
                        if (inputBuffer.available(3)) {
                            int length = inputBuffer.peek24BitInteger();
                            if (inputBuffer.available(FRAME_HEADER_SIZE + length)) {
                                handleFrame();
                            } else {
                                needMoreData = true;
                            }
                        } else {
                            needMoreData = true;
                        }
                    }
                    // If we somehow get a frame that indicates that the connection should close, then
                    // we will enter this state.
                    case CLOSED -> {
                        close();
                    }
                }
            }
        } catch (IOException e) {
            // If an IOException happens, then there is a problem with the underlying connection, and
            // we should close.
            e.printStackTrace(); // TODO This should not be printed out, except maybe in TRACE to a log
            close();
        } catch (Http2Exception e) {
            // If this exception happens, it means that something is wrong with one of the connection frames.
            // This is terminal. We're being kind and letting the client know of the problem before
            // we shut down the connection.
            try {
                RstStreamFrame.write(outputBuffer, e.getCode(), e.getStreamId());
            } catch (IOException ee) {
                System.err.println("Failed to write RST_STREAM frame out, connection is broken!");
                close();
            }
        }
        return false;
    }

    @Override
    public boolean doHandle(Consumer<HttpVersion> onConnectionUpgrade) {
        return false;
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

    /**
     * Decodes the next frame from the input buffer, and dispatches it to the appropriate method to handle.
     * There <b>MUST</b> be the entire frame's worth of data in the input buffer before this is called,
     * or an exception will be thrown causing the entire connection to terminate.
     *
     * @throws IOException Thrown if something goes wrong while handling the connection-level frames or the
     *                     connection input or output streams themselves, representing a broken connection.
     */
    private void handleFrame() throws IOException {
        final var type = FrameType.valueOf(inputBuffer.peekByte(3));
        switch (type) {
            case SETTINGS -> handleSettings();
            case HEADERS -> handleHeaders();
            case RST_STREAM -> handleRstStream();
            case PING -> handlePing();
            // SPEC: 4.1 Frame Format
            // Implementations MUST ignore and discard frames of unknown types.
            default -> skipUnknownFrame();
        }
    }

    /**
     * Skips the current frame.
     */
    private void skipUnknownFrame() {
        final var frameLength = inputBuffer.peek24BitInteger();
        inputBuffer.skip(frameLength + FRAME_HEADER_SIZE);
    }

    /**
     * Handles a "ping" frame. This is a physical connection level frame.
     *
     * @throws IOException thrown if we cannot write to the output channel
     */
    private void handlePing() throws IOException {
        final var pingData = PingFrame.parseData(inputBuffer);
        outputBuffer.reset();
        PingFrame.writeAck(outputBuffer, pingData);
        outputBuffer.sendContentsToChannel(channel);
    }

    /**
     * Handles new settings coming from the client. Settings only apply to the physical connection context.
     *
     * @throws IOException thrown if we cannot write to the output channel
     */
    private void handleSettings() throws IOException {
        SettingsFrame.parseAndMerge(inputBuffer, clientSettings);
        outputBuffer.reset();
        SettingsFrame.writeAck(outputBuffer);
        outputBuffer.sendContentsToChannel(channel);
    }

    /**
     * The headers frame indicates the start of a new stream. If an existing stream already exists,
     * it is a big problem! The spec doesn't say what should happen. So I'll start by being belligerent.
     */
    private void handleHeaders() {
        final var headerFrame = HeadersFrame.parse(inputBuffer);
        final var streamId = headerFrame.getStreamId();

        // Please, don't try to send the same headers frame twice...
        if (streams.containsKey(streamId)) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        // OK, so we have a new stream. That's great! Let's create a new Http2RequestContext to
        // handle it. When it is closed, we need to remove it from the map.
        final var requestCtx = contextReuseManager.checkoutHttp2RequestContext();
        requestCtx.reset(id -> streams.remove(id), channel);

        // Put it in the map
        streams.put(streamId, requestCtx);

        // NOW initialize it. If we initialize first, the header might be the kind that results in the end of
        // the stream, embodying the whole request, which would cause the onClose to be called, so we want
        // to make sure we added things to the map first so it can then be removed
        requestCtx.handleHeaders(headerFrame);
    }

    /**
     * Decodes and dispatches the RST_STREAM frame to the appropriate stream. This cannot
     * refer to the physical connection, only one of the logical connection streams.
     */
    private void handleRstStream() {
        final var frame = RstStreamFrame.parse(inputBuffer);
        final var streamId = frame.getStreamId();
        final var stream = streams.get(streamId);
        if (stream != null) {
            stream.handleRstStream(frame.getErrorCode());
        }
    }
}
