package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.*;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.http2.frames.*;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;
import com.hedera.hashgraph.web.impl.session.RequestContext;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import com.hedera.hashgraph.web.impl.util.ReusableByteArrayInputStream;
import com.hedera.hashgraph.web.impl.util.ReusableByteArrayOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents an HTTP/2 stream. A stream has a definite request/response lifecycle.
 *
 * <p>The HTTP2 spec has two states for the end of the lifecycle of a request called {@link State#HALF_CLOSED}
 * and {@link State#CLOSED}. The HALF_CLOSED state is entered into when the request has been fully made,
 * that is, the headers and request body (if any) have been received. In this state, responses can be formulated
 * and sent to the client, but no new request information received from the client. The CLOSED state means the
 * request and responses are complete. This is a terminal state.
 *
 * <p>The {@link Dispatcher} will handle the request, and in the case of <b>ANY</b> error, will update the
 * response with the appropriate state. It then calls {@link WebResponse#close()}, which should move the stream
 * into the {@link State#HALF_CLOSED} state. If some kind of stream exception occurs, it will cause the stream to
 * {@link #terminate()}. Or, if the stream finishes sending all the response data, then it will also call
 * {@link #terminate()}.
 */
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
         * state, we will move the {@link Http2Stream} back into the {@link ContextReuseManager}
         * and stop accepting frames. We'll just drop them on the floor.
         */
        CLOSED
    }

    /**
     * The HTTP/2 stream ID associated with this request. This value is set during
     * {@link #init(Http2Connection, int, Settings)} and set to -1 during {@link #terminate()}.
     */
    private int streamId = -1;

    /**
     * The current state of this stream. This value is set to {@link State#IDLE} during
     * {@link #init(Http2Connection, int, Settings)} and set to {@link State#CLOSED} during
     * {@link #terminate()}.
     */
    private State state = State.IDLE;

    /**
     * A reference to the {@link Http2Connection} that is using this stream. It contains some API
     * that the stream needs, for example, to decode and encode header data, or to submit data to
     * be sent to the client. This value is set during {@link #init(Http2Connection, int, Settings)}
     * and set back to null when the stream is terminated with {@link #terminate()}.
     */
    private Http2Connection connection;

    /**
     * This future represents the currently submitted job to the {@link Dispatcher}. When the stream
     * is {@link State#CLOSED}, then this job will be canceled if it has not been completed.
     * Canceling the job will only cause the associated thread to be interrupted, it is up to the
     * application to respect the {@link InterruptedException} and stop working.
     */
    private Future<?> submittedJob;

    /**
     * The current number of "credits", which describe the number of bytes we can send to the client.
     * While we give it a default value here, it truly doesn't matter because it will be reset in
     * {@link #init(Http2Connection, int, Settings)}.
     */
    private final AtomicInteger streamWindowCredits = new AtomicInteger(Settings.DEFAULT_INITIAL_WINDOW_SIZE);

    /**
     * An implementation of {@link WebRequest} for HTTP/2.
     */
    private final Http2WebRequest webRequest = new Http2WebRequest(Settings.INITIAL_FRAME_SIZE);

    /**
     * An implementation of {@link WebResponse} for HTTP/2.
     */
    private final Http2WebResponse webResponse = new Http2WebResponse();

    /**
     * A {@link RstStreamFrame} that can be reused for parsing reset frame data
     */
    private final RstStreamFrame rstStreamFrame = new RstStreamFrame();

    // =================================================================================================================
    // Constructor & Lifecycle

    /**
     * Create a new instance.
     *
     * @param dispatcher The {@link Dispatcher} to use. Must not be null.
     */
    public Http2Stream(final Dispatcher dispatcher) {
        super(dispatcher, HttpVersion.HTTP_2);
    }

    /**
     * Resets the instance prior to use.
     *
     * @param connection An implementation of {@link Http2Connection}
     */
    public void init(final Http2Connection connection, int streamId, Settings clientSettings) {
        super.reset();
        this.streamId = streamId;
        this.state = State.IDLE;
        this.connection = Objects.requireNonNull(connection);
        this.submittedJob = null;
        this.streamWindowCredits.set((int) clientSettings.getInitialWindowSize());
        this.webRequest.init();
        this.webResponse.init();
    }

    /**
     * Terminates the stream, cleaning up any remaining open resources, and returns the stream
     * to the pool.
     */
    void terminate() {
        if (this.state != State.CLOSED) {
            // Try to interrupt the handler thread, if there is one.
            if (submittedJob != null) {
                submittedJob.cancel(true);
            }

            // Let the connection know that the stream is closed
            connection.onTerminate(streamId);

            // Reset the internal state to uninitialized values
            this.streamId = -1;
            this.state = State.CLOSED;
            this.connection = null;
            this.submittedJob = null;
            this.rstStreamFrame.reset();
        }
    }

    /**
     * Handler for stream exceptions.
     *
     * @param exception The exception to handle.
     */
    private void onStreamException(Http2Exception exception) {
        if (state != State.CLOSED) {
            // SPEC: 5.4.2 Stream Error Handling
            // An endpoint that detects a stream error sends a RST_STREAM frame (Section 6.4) that contains the stream
            // identifier of the stream where the error occurred. The RST_STREAM frame includes an error code that
            // indicates the type of error.
            final OutputBuffer outputBuffer = connection.getOutputBuffer();
            rstStreamFrame.setStreamId(streamId)
                    .setErrorCode(exception.getCode())
                    .write(outputBuffer);
            connection.sendOutput(outputBuffer);
            connection.onBadClient(streamId);

            // Shut down the stream
            terminate();
        }
    }

    // =================================================================================================================
    // Methods called by other members of the package

    /**
     * Updates the flow control credits by some delta.
     *
     * @param delta The delta to apply to the flow control credits.
     */
    void updateFlowControlCredits(long delta) {
        streamWindowCredits.addAndGet((int) delta);
    }

    /**
     * Called by the {@link Http2Connection} to handle a headers frame. It has already been validated
     * for internal consistency and for any connection-level checks. This method must perform additional
     * stream-level validation and application of the header frame.
     *
     * @param frame The frame. Must not be null.
     */
    void handleHeadersFrame(final HeadersFrame frame) {
        assert frame.getStreamId() == streamId :
                "Stream ID Mismatch! Expected=" + streamId + ". Was=" + frame.getStreamId();

        // SPEC: Section 8.1.2.6
        // An endpoint that receives a HEADERS frame without the END_STREAM flag set after receiving a final
        // (non-informational) status code MUST treat the corresponding request or response as malformed
        // (Section 8.1.2.6).
        if (state == State.OPEN) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

        try {
            // TODO An endpoint receiving HEADERS, PUSH_PROMISE, or CONTINUATION frames needs to reassemble field blocks and perform
            //      decompression even if the frames are to be discarded. A receiver MUST terminate the connection with a connection
            //      error (Section 5.4.1) of type COMPRESSION_ERROR if it does not decompress a field block.

            // SPEC: 5.1 Stream States (half-closed (remote))
            // If an endpoint receives additional frames, other than WINDOW_UPDATE, PRIORITY, or RST_STREAM, for a
            // stream that is in this state, it MUST respond with a stream error (Section 5.4.2)
            // of type STREAM_CLOSED.
            assert state != State.CLOSED : "Closed stream received a HEADERS frame. StreamId=" + streamId;
            if (state == State.HALF_CLOSED) {
                throw new Http2Exception(Http2ErrorCode.STREAM_CLOSED, frame.getStreamId());
            }

            // SPEC: 5.1 Stream States
            // Sending a HEADERS frame as a client, or receiving a HEADERS frame as a server, causes the stream to
            // become "open"
            if (state == State.IDLE) {
                state = State.OPEN;
            }

            // Append header data
            final var blockLength = frame.getBlockLength();
            if (blockLength > 0) {
                webRequest.appendHeaderBlockData(frame.getFieldBlockFragment(), blockLength);
            }

            // If this is the end of the headers, then process them
            if (frame.isEndHeaders()) {
                webRequest.parseHeaders();
            }

            // SPEC: 5.1 Stream States (idle)
            // The same HEADERS frame can also cause a stream to immediately become "half-closed".
            if (frame.isEndStream()) {
                dispatch();
            }
        } catch (Http2Exception streamException) {
            onStreamException(streamException);
        }
    }

    /**
     * Called by the {@link Http2Connection} to handle a CONTINUATION frame. It has already been validated
     * for internal consistency and for any connection-level checks. This method must perform additional
     * stream-level validation and application of the CONTINUATION frame.
     *
     * @param frame The frame. Must not be null.
     */
    void handleContinuationFrame(final ContinuationFrame frame) {
        assert frame.getStreamId() == streamId :
                "Stream ID Mismatch! Expected=" + streamId + ". Was=" + frame.getStreamId();

        try {
            // TODO An endpoint receiving HEADERS, PUSH_PROMISE, or CONTINUATION frames needs to reassemble field blocks and perform
            //      decompression even if the frames are to be discarded. A receiver MUST terminate the connection with a connection
            //      error (Section 5.4.1) of type COMPRESSION_ERROR if it does not decompress a field block.

            // SPEC: 5.1 Stream States
            // Receiving any frame other than HEADERS or PRIORITY on a stream in [IDLE] state MUST be treated as a
            // connection error (Section 5.4.1) of type PROTOCOL_ERROR.
            if (state == State.IDLE) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
            }

            // SPEC: 5.1 Stream States (half-closed (remote))
            // If an endpoint receives additional frames, other than WINDOW_UPDATE, PRIORITY, or RST_STREAM, for a
            // stream that is in this state, it MUST respond with a stream error (Section 5.4.2)
            // of type STREAM_CLOSED.
            assert state != State.CLOSED : "Closed stream. StreamId=" + streamId;
            if (state == State.HALF_CLOSED) {
                throw new Http2Exception(Http2ErrorCode.STREAM_CLOSED, frame.getStreamId());
            }

            // Append header data
            final var blockLength = frame.getBlockLength();
            if (blockLength > 0) {
                webRequest.appendHeaderBlockData(frame.getFieldBlockFragment(), blockLength);
            }

            // If this is the end of the headers, then process them
            if (frame.isEndHeaders()) {
                webRequest.parseHeaders();
            }
        } catch (Http2Exception streamException) {
            onStreamException(streamException);
        }
    }

    /**
     * Called by the {@link Http2Connection} to handle a DATA frame. It has already been validated
     * for internal consistency and for any connection-level checks. This method must perform additional
     * stream-level validation and application of the DATA frame.
     *
     * @param frame The frame. Must not be null.
     */
    void handleDataFrame(final DataFrame frame) {
        assert streamId == -1 || frame.getStreamId() == streamId :
                "Stream ID Mismatch! Expected=" + streamId + ". Was=" + frame.getStreamId();

        try {
            // SPEC: 5.1 Stream States
            // Receiving any frame other than HEADERS or PRIORITY on a stream in [IDLE] state MUST be treated as a
            // connection error (Section 5.4.1) of type PROTOCOL_ERROR.
            if (state == State.IDLE) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, frame.getStreamId());
            }

            // SPEC: 5.1 Stream States (half-closed (remote))
            // If an endpoint receives additional frames, other than WINDOW_UPDATE, PRIORITY, or RST_STREAM, for a
            // stream that is in this state, it MUST respond with a stream error (Section 5.4.2)
            // of type STREAM_CLOSED.
            assert state != State.CLOSED : "Closed stream. StreamId=" + streamId;
            if (state == State.HALF_CLOSED) {
                throw new Http2Exception(Http2ErrorCode.STREAM_CLOSED, frame.getStreamId());
            }

            // NOTE: We do not bother with sending the client any "window update frames" for the data
            // we receive, because we know that the entire request (plus some!) can fit into the default
            // window, so we don't bother allowing the client to send yet more data!

            // Give the web request the new data
            final var dataLength = frame.getDataLength();
            webRequest.appendRequestBodyData(frame.getData(), dataLength);

            // If this is the last frame of data, then initiate the request!
            if (frame.isEndStream()) {
                dispatch();
            }
        } catch (Http2Exception streamException) {
            onStreamException(streamException);
        }
    }

    /**
     * Called by the {@link Http2Connection} to handle an RST_STREAM frame. It has already been validated
     * for internal consistency and for any connection-level checks. This method must perform additional
     * stream-level validation and application of the RST_STREAM frame. This type of frame is sent from
     * the client when it wants to prematurely terminate the request.
     *
     * @param frame The frame. Must not be null.
     */
    void handleRstStreamFrame(final RstStreamFrame frame) {
        assert frame.getStreamId() == streamId :
                "Stream ID Mismatch! Expected=" + streamId + ". Was=" + frame.getStreamId();

        try {
            // SPEC: 5.1 Stream States
            // Receiving any frame other than HEADERS or PRIORITY on a stream in [IDLE] state MUST be treated as a
            // connection error (Section 5.4.1) of type PROTOCOL_ERROR.
            assert state != State.CLOSED : "Closed stream. StreamId=" + streamId;
            if (state == State.IDLE) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
            }

            // SPEC: 5.1 Stream States
            // Either endpoint can send a RST_STREAM frame from this [OPEN] state, causing it to transition
            // immediately to "closed".
            if (state == State.OPEN || state == State.HALF_CLOSED) {
                terminate();
            }
        } catch (Http2Exception streamException) {
            onStreamException(streamException);
        }
    }

    /**
     * Called by the {@link Http2Connection} to handle a WINDOW_UPDATE frame. It has already been validated
     * for internal consistency and for any connection-level checks. This method must perform additional
     * stream-level validation and application of the WINDOW_UPDATE frame.
     *
     * @param frame The frame. Must not be null.
     */
    void handleWindowUpdateFrame(final WindowUpdateFrame frame) {
        assert frame.getStreamId() == streamId :
                "Stream ID Mismatch! Expected=" + streamId + ". Was=" + frame.getStreamId();

        try {
            // SPEC: 5.1 Stream States
            // Receiving any frame other than HEADERS or PRIORITY on a stream in [IDLE] state MUST be treated as a
            // connection error (Section 5.4.1) of type PROTOCOL_ERROR.
            assert state != State.CLOSED : "Closed stream. StreamId=" + streamId;
            if (state == State.IDLE) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
            }

            // SPEC 6.9.1
            // A sender MUST NOT allow a flow-control window to exceed 231-1 octets. If a sender receives a WINDOW_UPDATE
            // that causes a flow-control window to exceed this maximum, it MUST terminate either the stream or the
            // connection, as appropriate. For streams, the sender sends a RST_STREAM with an error code of
            // FLOW_CONTROL_ERROR; for the connection, a GOAWAY frame with an error code of FLOW_CONTROL_ERROR is sent.
            final var increment = frame.getWindowSizeIncrement();
            final long credits = streamWindowCredits.get();
            final var wouldOverflow = credits + increment > Settings.MAX_FLOW_CONTROL_WINDOW_SIZE;
            if (wouldOverflow) {
                throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR, streamId);
            }

            updateFlowControlCredits(increment);
        } catch (Http2Exception streamException) {
            onStreamException(streamException);
        }
    }

    /**
     * Called by the {@link Http2Connection} to handle a PRIORITY frame. It has already been validated
     * for internal consistency and for any connection-level checks. This method must perform additional
     * stream-level validation and application of the PRIORITY frame.
     *
     * @param priorityFrame The frame. Must not be null.
     */
    void handlePriorityFrame(final PriorityFrame priorityFrame) {
        assert state != State.CLOSED : "Closed stream. StreamId=" + streamId;
        // TODO I don't support this, but if I get one, does the stream become open?
        // If I get priority followed by headers, does it become half-closed?

        // Sending or receiving a PRIORITY frame does not affect the state of any stream (Section 5.1). The PRIORITY frame can be sent on a stream in any state, including "idle" or "closed". A PRIORITY frame cannot be sent between consecutive frames that comprise a single field block (Section 4.3).
    }

    /**
     * Called to dispatch the web request.
     */
    private void dispatch() {
        // Check for "content-length"
        final var contentLength = webRequest.requestHeaders.getContentLength();
        if (contentLength >= 0) {
            if (webRequest.requestBodyLength != contentLength) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
            }
        }

        state = State.HALF_CLOSED;
        submittedJob = dispatcher.dispatch(webRequest, webResponse);
    }

    /**
     * Represents an HTTP/2 {@link WebRequest}. A single instance exists per {@link Http2Stream} instance,
     * and is reused.
     */
    private final class Http2WebRequest implements WebRequest {
        /**
         * The request headers. This instance is reused between requests. After we read all the header data,
         * we parse it and set the headers in the {@link Http2Headers} instance.
         */
        private final Http2Headers requestHeaders = new Http2Headers();

        /**
         * The request body array.
         *
         * <p>At first, we place the compressed header data into this buffer, and then after decoding the headers
         * and storing them in {@link #requestHeaders}, we reset the stream and use it for storing the request
         * body data.
         */
        private final byte[] requestBody;

        /**
         * The number of used bytes in the {@link #requestBody}.
         */
        private int requestBodyLength = 0;

        /**
         * A reusable {@link java.io.InputStream} that we use for passing to the handler the request body stream.
         */
        private final ReusableByteArrayInputStream requestBodyInputStream;

        /**
         * Create a new instance.
         *
         * @param numBytes The size of the request body buffer. No requests can exceed this size.
         */
        Http2WebRequest(int numBytes) {
            this.requestBody = new byte[numBytes];
            this.requestBodyInputStream = new ReusableByteArrayInputStream(requestBody);
        }

        /**
         * Called to re-initialize the request.
         */
        void init() {
            this.requestBodyLength = 0;
            this.requestHeaders.clear();
            this.requestBodyInputStream.reuse(0);
        }

        /**
         * Appends a block of compressed header data.
         *
         * @param fieldBlockFragment The field block fragment. Cannot be null.
         * @param blockLength The number of bytes in the array that are meaningful. Must be non-negative.
         */
        void appendHeaderBlockData(byte[] fieldBlockFragment, int blockLength) {
            System.arraycopy(fieldBlockFragment, 0, requestBody, requestBodyLength, blockLength);
            webRequest.requestBodyLength += blockLength;
        }

        /**
         * Signals the end of header fragment data accumulation. Parses the header data, populates the
         * {@link #requestHeaders}, and resets the {@link #requestBodyLength}.
         */
        void parseHeaders() {
            try {
                final var codec = connection.getHeaderCodec();
                requestBodyInputStream.length(requestBodyLength);
                codec.decode(requestHeaders, requestBodyInputStream, streamId);
                requestBodyLength = 0;

                if (requestHeaders.getMethod() == null) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
                }

                if (requestHeaders.getScheme() == null) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
                }

                final var path = requestHeaders.getPath();
                if (path == null || path.isBlank()) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
                }
            } catch (IOException ex) {
                // SPEC: 4.3 Field Section Compression and Decompression
                // A decoding error in a field block MUST be treated as a connection error (Section 5.4.1) of type
                // COMPRESSION_ERROR.
                ex.printStackTrace();
                throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, streamId);
            }
        }

        /**
         * Appends request body data to the array.
         *
         * @param data A non-null array of bytes to append
         * @param length The number of meaningful bytes. Must be non-negative.
         */
        void appendRequestBodyData(byte[] data, int length) {
            System.arraycopy(data, 0, requestBody, requestBodyLength, length);
            requestBodyLength += length;
        }

        // =================================================================================================================
        // WebRequest Methods

        @Override
        public WebHeaders getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public InputStream getRequestBody() {
            requestBodyInputStream.length(requestBodyLength);
            return requestBodyInputStream;
        }

        @Override
        public String getMethod() {
            return requestHeaders.getPseudoHeader(":method");
        }

        @Override
        public String getPath() {
            return requestHeaders.getPseudoHeader(":path");
        }

        @Override
        public HttpVersion getVersion() {
            return version;
        }
    }

    /**
     * An implementation of an HTTP/2 {@link WebResponse}. A single instance exists per {@link Http2Stream} instance,
     * and is reused.
     */
    private final class Http2WebResponse implements WebResponse {
        /**
         * Holds the response headers
         */
        private final Http2Headers responseHeaders = new Http2Headers();

        /**
         * All response body content is sent to this buffer. We don't need to keep all the response
         * data, only enough to send a frame to the client at a time.
         */
        private final OutputBufferOutputStream responseBuffer = new OutputBufferOutputStream();

        /**
         * A {@link HeadersFrame} that can be reused for responding with header data. The byte[] within
         * the headers frame is large enough to hold the compressed headers.
         */
        private final HeadersFrame responseHeadersFrame = new HeadersFrame();

        /**
         * This reusable output stream will write compressed header data into the {@link #responseHeadersFrame}.
         */
        private final ReusableByteArrayOutputStream compressedHeaderStream =
                new ReusableByteArrayOutputStream(responseHeadersFrame.getFieldBlockFragment());

        /**
         * True after headers have been sent.
         */
        private boolean headersSent = false;

        /**
         * This flag is used to determine whether the response has already been closed.
         */
        private boolean closed = false;

        /**
         * Create a new instance.
         */
        Http2WebResponse() {
        }

        void init() {
            this.responseHeaders.clear();
            this.responseBuffer.reset();
            this.responseHeadersFrame.reset();
            this.closed = false;
            this.headersSent = false;
        }

        // =================================================================================================================
        // WebRequest Methods

        @Override
        public WebHeaders getHeaders() {
            return responseHeaders;
        }

        @Override
        public WebResponse statusCode(StatusCode code) {
            responseHeaders.putStatus(code);
            return this;
        }

        @Override
        public void respond(StatusCode code) {
            statusCode(code);
            close();
        }

        @Override
        public void respond(String contentType, String bodyAsString, Charset charset) {
            if (bodyAsString != null) {
                respond(contentType, bodyAsString.getBytes(charset));
            }
            close();
        }

        @Override
        public void respond(String contentType, byte[] bodyAsBytes)  {
            contentType(contentType);
            if (bodyAsBytes != null && bodyAsBytes.length > 0) {
                responseBuffer.write(bodyAsBytes, 0, bodyAsBytes.length);
            }
            close();
        }


        @Override
        public OutputStream respond(String contentType) throws IOException {
            contentType(contentType);
            return responseBuffer;
        }

        @Override
        public OutputStream respond(String contentType, int contentLength) throws IOException {
            contentType(contentType);
            // TODO what should I do with contentLength?
            return responseBuffer;
        }

        @Override
        public void close() {
            // Do nothing if close has already been called on this response. Only respond
            // when the stream is in the HALF_CLOSED state.
            if (!closed && state == State.HALF_CLOSED) {
                try {
                    // Encode the headers into a packed, compressed set of bytes, and write them to the buffer.
                    final var length = connection.getHeaderCodec().encode(responseHeaders, compressedHeaderStream);

                    // Now we have an actual length, we can go back and edit a few bytes of the header with the length
                    responseHeadersFrame
                            .setStreamId(streamId)
                            .setEndHeaders(true)
                            .setEndStream(responseBuffer.currentBuffer.size() == 0)
                            .setBlockLength(length);

                    // Send the header frame
                    final var buf = connection.getOutputBuffer();
                    responseHeadersFrame.write(buf);
                    connection.sendOutput(buf);
                    responseBuffer.sendRequestOutputBufferContentsAsLastFrame();
                } catch (IOException encodeException) {
                    encodeException.printStackTrace();
                    final var streamException = new Http2Exception(Http2ErrorCode.INTERNAL_ERROR, streamId);
                    onStreamException(streamException);
                }

                responseBuffer.sendRequestOutputBufferContentsAsLastFrame();
                closed = true;
                terminate();

                // TODO We need to make sure that if the stream happens to be in the middle of handling
                //      on another thread and comes back from there, that it doesn't ever do anything.
                //      It may be that the Http2Stream is reused, and by the time the handler
                //      comes back, it finds that the Http2Stream has been reused and in a
                //      half-closed state ready for a response! We need to cancel that job and make
                //      sure it never comes back (back to the Future!!)
            }
        }
    }

    public final class OutputBufferOutputStream extends OutputStream {
        private OutputBuffer currentBuffer;

        OutputBufferOutputStream() {
        }

        void reset() {
            if (currentBuffer == null) {
                checkoutBuffer();
            } else {
                currentBuffer.reset();
            }
            Frame.writeHeader(currentBuffer, 0, FrameType.DATA, (byte) 0, streamId);
        }

        private void sendRequestOutputBufferContentsAsFrame() {
            submitFrame(false);
        }

        private void sendRequestOutputBufferContentsAsLastFrame() {
            submitFrame(true);
        }

        private void checkoutBuffer() {
            currentBuffer = connection.getOutputBuffer();
            currentBuffer.setOnDataFullCallback(this::sendRequestOutputBufferContentsAsLastFrame);
            currentBuffer.setOnCloseCallback(this::sendRequestOutputBufferContentsAsFrame);
            currentBuffer.reset();
        }

        private void submitFrame(boolean endOfStream) {
            // The primary complication of this method is in dealing with flow control windows. There
            // are two windows we have to pay attention to -- the per-connection window and the
            // per-stream window. So we have a lock given to us from the connection which we can use
            // to lock around these flow control things (including creating a mutual exclusion lock
            // between streams sending data and the connection handling new settings and new window
            // update frames).
            //
            // It is possible that we will have more data to send than can be sent in a single data frame.
            // So we will use a loop and keep sending frames from this stream's thread until all the
            // data is sent. This means that the application's "handler" thread will block until
            // all these frames finally make their way to the client.
            //
            // For each iteration, we figure out the maximum amount of data we can send, and adjust
            // the flow control credits accordingly (on both the stream and the connection), and then
            // release the lock so others have a chance to try to send data.
            //
            // Unfortunately, if we cannot send all the data in one go, we will need to make buffer
            // copies, because once we send a buffer to the connection, it's lifecycle is out of
            // our hands.
            boolean allDataSent = false;
            while (!allDataSent && state != State.CLOSED) {
                connection.getFlowControlLock().lock();
                try {
                    // Check both the connection window credits and the stream credits to compute how
                    // much maximum data we can send to the client.
                    final var connectionWindowCredits = connection.getFlowControlCredits();
                    final var maxDataSizeInWindow = Math.min(connectionWindowCredits.get(), streamWindowCredits.get());

                    // If we cannot send any data to the client, then wait until later.
                    if (maxDataSizeInWindow > 0) {
                        // Compute the actual amount of data we will send
                        final var bufferToSend = currentBuffer;
                        final var dataLengthToSend = Math.min(maxDataSizeInWindow, bufferToSend.size());
                        final var bufferDataLength = bufferToSend.size();

                        // If we cannot send all the data, then get a new buffer and copy all the data
                        // we couldn't send, into the new buffer
                        checkoutBuffer();
                        Frame.writeHeader(currentBuffer, 0, FrameType.DATA, (byte) 0, streamId);
                        if (dataLengthToSend < bufferDataLength) {
                            bufferToSend.position(Frame.FRAME_HEADER_SIZE + dataLengthToSend);
                            currentBuffer.write(bufferToSend);
                            bufferToSend.getBuffer().limit(Frame.FRAME_HEADER_SIZE + dataLengthToSend);
                        } else {
                            allDataSent = true;
                        }

                        // Now write out the data frame. Put the length in.
                        bufferToSend.position(0);
                        bufferToSend.write24BitInteger(dataLengthToSend);
                        bufferToSend.position(4);
                        bufferToSend.writeByte(endOfStream ? 1 : 0);
                        bufferToSend.position(Frame.FRAME_HEADER_SIZE + dataLengthToSend);
                        streamWindowCredits.addAndGet(-dataLengthToSend);
                        connectionWindowCredits.addAndGet(-dataLengthToSend);
                        connection.sendOutput(bufferToSend);
                    }
                } finally {
                    connection.getFlowControlLock().unlock();
                }

                if (!allDataSent) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ignored) {
                        // If we were interrupted, then quite likely the thread is being terminated.
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }

        @Override
        public void write(int b) {
            currentBuffer.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            currentBuffer.write(b, off, len);
        }
    }

}
