package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.*;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.http2.frames.*;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;
import com.hedera.hashgraph.web.impl.session.RequestContext;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import com.hedera.hashgraph.web.impl.util.ReusableByteArrayInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;

// TODO I'm being less rigorous on my buffer sizes than I should be -- headers can fill up a buffer of
// size N, and then when decompressed they can fill up another buffer of size N -- could overflow.
public final class Http2Stream extends RequestContext {
    /**
     * The different states supported by the HTTP/2.0 Stream States state machine.
     * See the specification, Figure 2, and in section 5.1
     */
    private enum State {
        /**
         * The initial state of the stream. It usually will only be in this state until
         * the first HEADERS frame is received (which in our case, happens immediately
         * when removing an {@link Http2Stream} from the {@link ContextReuseManager}).
         */
        IDLE,
        /**
         * A stream enters this state when the HEADERS frame arrives. if the HEADER frame has
         * the "end of stream" flag set, then it immediately transitions to {@link #HALF_CLOSED}.
         * Otherwise, it remains in this state until the entire request has been sent
         * to the server.
         */
        OPEN,
        /**
         * The stream is now processing the request and preparing a response. There are very
         * few frame types still allowed from the client to the server on this stream, and
         * few that the server can send to the client.
         */
        HALF_CLOSED,
        /**
         * The stream is closed and accepts virtually nothing. In fact, when we enter the closed
         * state, we will move the {@link Http2Stream} back into the
         * {@link ContextReuseManager} and stop accepting frames. We'll just drop them on the floor.
         */
        CLOSED,
        /**
         * This state is unused, because we do not support PUSH_PROMISE frames (this server doens't
         * use them, and the client cannot send them to us).
         */
        RESERVED
    }

    /**
     * Each field block is processed as a discrete unit. Field blocks MUST be transmitted as a contiguous sequence of
     * frames, with no interleaved frames of any other type or from any other stream. The last frame in a sequence of
     * HEADERS or CONTINUATION frames has the END_HEADERS flag set. The last frame in a sequence of PUSH_PROMISE or
     * CONTINUATION frames has the END_HEADERS flag set. This allows a field block to be logically equivalent to a
     * single frame.
     */
    private boolean headerContinuation = false;

    /**
     * The HTTP/2.0 stream ID associated with this request
     */
    private int streamId;

    /**
     * The current state of this stream.
     */
    private State state = State.IDLE;

    /**
     * A buffer into which we copy the request data as it comes in. At first, we place the decoded header
     * data into this buffer, and then after turning that decoded header data into a {@link #requestHeaders}
     * field, we initialize the buffer and use it for storing the request body data.
     */
    private final byte[] requestBuffer;

    /**
     * The index of the end of data in the {@link #requestBuffer}.
     */
    private int requestBufferLength = 0;

    /**
     * A reusable {@link java.io.InputStream} that we use for passing to the handler the
     * request body stream.
     */
    private final ReusableByteArrayInputStream requestBodyInputStream;

    /**
     * Some data associated with the connection that the request needs.
     */
    private Http2Connection connection;

    /**
     * The request headers. This instance is reused between requests. After we read all the header data,
     * we parse it and set the headers in this the {@link Http2Headers} instance.
     */
    private final Http2Headers requestHeaders = new Http2Headers();

    /**
     * This buffer holds the response data. At first, it is filled with the header frame, until that is sent,
     * and then with a data frame and one or more continuation frames until they are ready to be sent,
     * or any other frames we need to prepare to send.
     */
    private final OutputBuffer responseBuffer = new OutputBuffer(
            Settings.INITIAL_FRAME_SIZE,
            this::sendRequestOutputBufferContentsAsLastFrame, // when user closes stream send data as frame
            this::sendRequestOutputBufferContentsAsFrame);  // if the user write too much data then send in multiple frames

    // =================================================================================================================
    // Constructor & Methods

    /**
     * Create a new instance.
     *
     * @param dispatcher The {@link Dispatcher} to use. Must not be null.
     */
    public Http2Stream(final Dispatcher dispatcher) {
        super(dispatcher);
        this.version = HttpVersion.HTTP_2;

        this.requestBuffer = new byte[Settings.INITIAL_FRAME_SIZE];
        this.requestBodyInputStream = new ReusableByteArrayInputStream(requestBuffer);
    }

    /**
     * Resets the instance prior to use.
     *
     * @param connection An implementation of {@link Http2Connection}
     */
    public void init(Http2Connection connection) {
        super.reset();
        this.state = State.IDLE;
        this.streamId = -1;
        this.connection = Objects.requireNonNull(connection);
        this.responseBuffer.reset();
        this.requestBufferLength = 0;
        this.requestBodyInputStream.reuse(0);
        this.requestHeaders.clear();
    }

    private void sendRequestOutputBufferContentsAsLastFrame() {
        try {
            // Write the body data
            responseBuffer.position(0);
            responseBuffer.write24BitInteger(responseBuffer.size() - Frame.FRAME_HEADER_SIZE);
            responseBuffer.position(4);
            responseBuffer.writeByte((byte) 0x1); // END OF STREAM
            sendFrame();
        } catch (IOException fatalToConnection) {
            fatalToConnection.printStackTrace();
        }
    }

    private void sendRequestOutputBufferContentsAsFrame() {
        // THIS CALLBACK HAPPENS WHEN WE FILL UP BECAUSE WE HAD TOO MUCH DATA
//        try {
//            // Create Frame Header
//            responseHeaderBuffer.init();
//            DataFrame.writeHeader(responseHeaderBuffer, streamId, responseBuffer.size());
//            sendFrame();
//        } catch (IOException fatalToConnection) {
//            fatalToConnection.printStackTrace();
//        }
    }

    private void sendFrame() throws IOException {
        connection.flush(responseBuffer);
    }

    // =================================================================================================================
    // WebRequest Methods

//    @Override
//    public OutputStream startResponse(final StatusCode statusCode, final WebHeaders responseHeaders) throws ResponseAlreadySentException {
//        try {
//            sendHeaders(statusCode, responseHeaders, false);
//        } catch (IOException fatalToConnection) {
//            fatalToConnection.printStackTrace();
//        }
//
//        // Now we just return the response buffer, which is itself an OutputStream. The handler will write
//        // whatever body bytes it wants into this stream. When it fills up, we get a callback, and can then
//        // send the frame and init for another. When it closes, we get a callback and can send the frame.
//        responseBuffer.reset();
//        DataFrame.writeHeader(responseBuffer, streamId, 0);
//        return responseBuffer;
//    }

//    @Override
//    public void respond(final StatusCode statusCode, final WebHeaders responseHeaders) throws ResponseAlreadySentException {
//        try {
//            sendHeaders(statusCode, responseHeaders, true);
//        } catch (IOException fatalToConnection) {
//            fatalToConnection.printStackTrace();
//        }
//    }

    @Override
    public WebHeaders getRequestHeaders() {
        return null;
    }

    @Override
    public WebResponse respond() throws ResponseAlreadySentException {
        return null;
    }

    @Override
    public void close() {
        connection.close(streamId);
        streamId = -1;
        connection = null;
    }

    // =================================================================================================================
    // Methods for handling different types of frames. These are called by the connection

    void handleDataFrame(final DataFrame dataFrame) {
        // SPEC: 5.1 Stream States
        // Receiving any frame other than HEADERS or PRIORITY on a stream in [IDLE] state MUST be treated as a
        // connection error (Section 5.4.1) of type PROTOCOL_ERROR.
        if (state == State.IDLE) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, dataFrame.getStreamId());
        }

        /*
            DATA frames are subject to flow control and can only be sent when a stream is in the "open" or
            "half-closed (remote)" state. The entire DATA frame payload is included in flow control, including the
            Pad Length and Padding fields if present. If a DATA frame is received whose stream is not in the "open" or
            "half-closed (local)" state, the recipient MUST respond with a stream error (Section 5.4.2) of type
            STREAM_CLOSED.
         */
    }

    void handleHeadersFrame(final HeadersFrame headerFrame) {
        // set the request id, and get all the header data decoded, and so forth.
        streamId = headerFrame.getStreamId();

        // SPEC: 5.1 Stream States
        // Sending a HEADERS frame as a client, or receiving a HEADERS frame as a server, causes the stream to
        // become "open"
        if (state == State.IDLE) {
            state = State.OPEN;
        }

        // TODO If I am already open, does the same headers frame cause me to go half-closed?
        // The same HEADERS frame can also cause a stream to immediately become "half-closed".

        if (headerFrame.isCompleteHeader()) {
            // I have all the bytes I will need... so I can go and decode them
            try {
                final var codec = connection.getHeaderCodec();
                codec.decode(requestHeaders, new ByteArrayInputStream(headerFrame.getFieldBlockFragment()));
                method = requestHeaders.getMethod();
                path = requestHeaders.getPath();
            } catch (IOException ex) {
                // SPEC: 4.3 Field Section Compression and Decompression
                // A decoding error in a field block MUST be treated as a connection error (Section 5.4.1) of type
                // COMPRESSION_ERROR.
                throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, streamId);
            }
        }

        if (headerFrame.isEndStream()) {
            state = State.HALF_CLOSED;
            dispatcher.dispatch(this);
        }
    }

    void handlePriorityFrame(PriorityFrame frame) {
        // TODO I don't support this, but if I get one, does the stream become open?
        // If I get priority followed by headers, does it become half-closed?

        // Sending or receiving a PRIORITY frame does not affect the state of any stream (Section 5.1). The PRIORITY frame can be sent on a stream in any state, including "idle" or "closed". A PRIORITY frame cannot be sent between consecutive frames that comprise a single field block (Section 4.3).
    }

    void handleRstStreamFrame(RstStreamFrame frame) {

        final int streamId = frame.getStreamId();
        final var errorCode = frame.getErrorCode();

        // SPEC: 5.1 Stream States
        // Receiving any frame other than HEADERS or PRIORITY on a stream in [IDLE] state MUST be treated as a
        // connection error (Section 5.4.1) of type PROTOCOL_ERROR.
        if (state == State.IDLE) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        if (state == State.OPEN || state == State.HALF_CLOSED) {
            state = State.CLOSED;
        }

        // SPEC: 5.1 Stream States
        // Either endpoint can send a RST_STREAM frame from this [OPEN] state, causing it to transition immediately to
        // "closed".
        System.out.println("RST_STREAM: " + errorCode.name());
        close();
    }

    void handlePushPromiseFrame(final int streamId) {
        // SPEC: 5.1 Stream States
        // Receiving any frame other than HEADERS or PRIORITY on a stream in [IDLE] state MUST be treated as a
        // connection error (Section 5.4.1) of type PROTOCOL_ERROR.
        if (state == State.IDLE) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

    }

    void handleWindowUpdateFrame(final int streamId) {
        // SPEC: 5.1 Stream States
        // Receiving any frame other than HEADERS or PRIORITY on a stream in [IDLE] state MUST be treated as a
        // connection error (Section 5.4.1) of type PROTOCOL_ERROR.
        if (state == State.IDLE) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

    }

    void handleContinuationFrame(final int streamId) {
        // SPEC: 5.1 Stream States
        // Receiving any frame other than HEADERS or PRIORITY on a stream in [IDLE] state MUST be treated as a
        // connection error (Section 5.4.1) of type PROTOCOL_ERROR.
        if (state == State.IDLE) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

    }

    /*
     * We initialize the first 9 bytes of the buffer with
     * the DATA_FRAME header information, with some empty bytes for things like the length. Then
     * we allow the response output stream to fill up the remaining bytes in the buffer. If the
     * request terminates before the buffer is totally full, we fill in the length and other flags
     * in the header section and send this off to the
     */

    /*
    From this state, either endpoint can send a frame with an END_STREAM flag set, which causes the stream to
    transition into one of the "half-closed" states. An endpoint sending an END_STREAM flag causes the stream state to
    become "half-closed (local)"; an endpoint receiving an END_STREAM flag causes the stream state to become
    "half-closed (remote)".

    Either endpoint can send a RST_STREAM frame from this state, causing it to transition immediately to "closed".
     */

    // TODO: To implement this, I need to process PUSH_PROMISE and CONTINUATION and make sure I always
    //       decompress the contents!!
    // An endpoint receiving HEADERS, PUSH_PROMISE, or CONTINUATION frames needs to reassemble field blocks and perform
    // decompression even if the frames are to be discarded. A receiver MUST terminate the connection with a connection
    // error (Section 5.4.1) of type COMPRESSION_ERROR if it does not decompress a field block.

    private void sendHeadersFrame(final StatusCode statusCode, final Http2Headers responseHeaders, boolean endOfStream) throws IOException {
        if (state == State.OPEN) {
            // Can send
            // TODO If send "end of stream", transition to HALF_CLOSED
        }

        // Initialize the response buffer and fill out some placeholder content for the frame header
        responseBuffer.reset();
        HeadersFrame.writeHeader(responseBuffer, streamId, 0, endOfStream);

        // Encode the headers into a packed, compressed set of bytes, and write them to the buffer.
        responseHeaders.putStatus(statusCode);
        final var codec = connection.getHeaderCodec();
        final var length = codec.encode(responseHeaders, responseBuffer);
        assert length == (responseBuffer.size() - Frame.FRAME_HEADER_SIZE) : "Length was unexpected!!";

        // Now we have an actual length, we can go back and edit a few bytes of the header with the length
        responseBuffer.position(0);
        responseBuffer.write24BitInteger(length);
        sendFrame();
    }

    private void sendWindowUpdateFrame() {
        if (state == State.OPEN || state == State.HALF_CLOSED) {
            // Can send
            // TODO If send "end of stream", transition to HALF_CLOSED
        }
    }

    private void sendPriorityFrame() {
        if (state == State.OPEN || state == State.HALF_CLOSED) {
            // Can send
            // TODO If send "end of stream", transition to HALF_CLOSED
        }
    }

    private void sendRstStreamFrame() {
        if (state == State.OPEN || state == State.HALF_CLOSED) {
            // Can send
            // Spec: 5.1 Stream States
            // Either endpoint can send a RST_STREAM frame from this state, causing it to transition immediately to
            // "closed".
            state = State.CLOSED;
        }
    }

}
