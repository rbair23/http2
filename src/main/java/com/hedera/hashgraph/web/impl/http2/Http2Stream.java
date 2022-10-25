package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.*;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.http2.frames.*;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;
import com.hedera.hashgraph.web.impl.session.RequestContext;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import com.hedera.hashgraph.web.impl.util.ReusableByteArrayInputStream;
import com.hedera.hashgraph.web.impl.util.ReusableByteArrayOutputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * Represents the {@link WebRequest} for an HTTP/2 stream.
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
         * state, we will move the {@link Http2Stream} back into the
         * {@link ContextReuseManager} and stop accepting frames. We'll just drop them on the floor.
         */
        CLOSED,
        /**
         * This state is unused, because we do not support PUSH_PROMISE frames (this server doesn't
         * use them, and the client cannot send them to us).
         */
        RESERVED
    }

    /**
     * The HTTP/2 stream ID associated with this request
     */
    private int streamId = -1;

    /**
     * The current state of this stream.
     */
    private State state = State.IDLE;

    /**
     * A reference to the {@link Http2Connection} that created this stream. It contains some API
     * that the stream needs, for example, to decode and encode header data, or to submit data to
     * be sent to the client.
     */
    private Http2Connection connection;

    /**
     * The request headers. This instance is reused between requests. After we read all the header data,
     * we parse it and set the headers in this the {@link Http2Headers} instance.
     */
    private final Http2Headers requestHeaders = new Http2Headers();

    /**
     * A reusable {@link java.io.InputStream} that we use for passing to the handler the
     * request body stream.
     *
     * <p>At first, we place the decoded header data into this buffer, and then after decoding the headers
     * and storing them in {@link #requestHeaders}, we reset the stream and use it for storing the request
     * body data.
     */
    private final ReusableByteArrayInputStream requestBodyInputStream;

    /**
     * The object we return to the user to collect the web response.
     */
    private final Http2WebResponse webResponse;

    private final OutputBufferOutputStream responseBuffer;

    /**
     * A {@link HeadersFrame} that can be reused for responding with header data. The byte[] within
     * the headers frame is large enough to hold the compressed headers.
     */
    private final HeadersFrame responseHeadersFrame = new HeadersFrame();

    /**
     * A {@link RstStreamFrame} that can be reused for sending reset frame data
     */
    private final RstStreamFrame responseRstStreamFrame = new RstStreamFrame();

    // =================================================================================================================
    // Constructor & Methods

    /**
     * Create a new instance.
     *
     * @param dispatcher The {@link Dispatcher} to use. Must not be null.
     */
    public Http2Stream(final Dispatcher dispatcher) {
        super(dispatcher, HttpVersion.HTTP_2);

        this.requestBodyInputStream = new ReusableByteArrayInputStream(new byte[Settings.INITIAL_FRAME_SIZE]);
        this.responseBuffer = new OutputBufferOutputStream();
        this.webResponse = new Http2WebResponse();
    }

    /**
     * Resets the instance prior to use.
     *
     * @param connection An implementation of {@link Http2Connection}
     */
    public void init(final Http2Connection connection, int streamId) {
        super.reset();
        this.state = State.IDLE;
        this.streamId = streamId;
        this.connection = Objects.requireNonNull(connection);
        this.requestBodyInputStream.reuse(0);
        this.requestHeaders.clear();
        this.responseBuffer.reset();
        this.webResponse.reset(responseBuffer);

        // TODO I should reset the frames, just to be sure no data is being reused...
    }

    // =================================================================================================================
    // WebRequest Methods

    @Override
    public WebHeaders getRequestHeaders() {
        return requestHeaders;
    }

    @Override
    public void close() {
        // TODO This is wrong. So, so, wrong.
        // If we are closed while in the HALF_CLOSED state, then maybe(???) this means we handled things
        // successfully (it really doesn't mean that, we need another signal).
        if (state == State.HALF_CLOSED) {
            try {
                final var responseHeaders = (Http2Headers) webResponse.getHeaders();
                final var endOfStream = responseBuffer.currentBuffer.size() == 0;
                sendHeadersFrame(responseHeaders, endOfStream);
                responseBuffer.sendRequestOutputBufferContentsAsLastFrame();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        sendRstStreamFrame();

        // TODO We need to make sure that if the stream happens to be in the middle of handling
        //      on another thread and comes back from there, that it doesn't ever do anything.
        //      It may be that the Http2Stream is reused, and by the time the handler
        //      comes back, it finds that the Http2Stream has been reused and in a
        //      half-closed state ready for a response! We need to cancel that job and make
        //      sure it never comes back (back to the Future!!)
        connection.close(streamId);
        streamId = -1;
        connection = null;
    }

    // =================================================================================================================
    // Methods for handling different types of frames. These are called by the connection

    void handleHeadersFrame(final HeadersFrame headersFrame) {
        // Set the request id, and get all the header data decoded, and so forth.
        streamId = headersFrame.getStreamId();

        // SPEC: 5.1 Stream States
        // Sending a HEADERS frame as a client, or receiving a HEADERS frame as a server, causes the stream to
        // become "open"
        if (state == State.IDLE) {
            state = State.OPEN;
        }

        // TODO If I am already open, does the same headers frame cause me to go half-closed?
        // The same HEADERS frame can also cause a stream to immediately become "half-closed".

        if (headersFrame.isEndHeaders() && headersFrame.getBlockLength() > 0) {
            // I have all the bytes I will need... so I can go and decode them
            try {
                final var codec = connection.getHeaderCodec();
                codec.decode(requestHeaders, new ByteArrayInputStream(headersFrame.getFieldBlockFragment(), 0, headersFrame.getBlockLength()));
                method = requestHeaders.getMethod();
                path = requestHeaders.getPath();
            } catch (IOException ex) {
                // SPEC: 4.3 Field Section Compression and Decompression
                // A decoding error in a field block MUST be treated as a connection error (Section 5.4.1) of type
                // COMPRESSION_ERROR.
                ex.printStackTrace();
                throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, streamId);
            }
        }

        // It is possible the entire request was just a HEADERS frame, in which case
        // we can transition directly to respond mode (HALF_CLOSED).
        if (headersFrame.isEndStream()) {
            state = State.HALF_CLOSED;
            dispatcher.dispatch(this, webResponse);
        }
    }

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

    void handlePriorityFrame(final PriorityFrame priorityFrame) {
        // TODO I don't support this, but if I get one, does the stream become open?
        // If I get priority followed by headers, does it become half-closed?

        // Sending or receiving a PRIORITY frame does not affect the state of any stream (Section 5.1). The PRIORITY frame can be sent on a stream in any state, including "idle" or "closed". A PRIORITY frame cannot be sent between consecutive frames that comprise a single field block (Section 4.3).
    }

    void handleRstStreamFrame(final RstStreamFrame rstStreamFrame) {
        final int streamId = rstStreamFrame.getStreamId();
        final var errorCode = rstStreamFrame.getErrorCode();

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

    void handleWindowUpdateFrame(final WindowUpdateFrame windowUpdateFrame) {
        // SPEC: 5.1 Stream States
        // Receiving any frame other than HEADERS or PRIORITY on a stream in [IDLE] state MUST be treated as a
        // connection error (Section 5.4.1) of type PROTOCOL_ERROR.
        if (state == State.IDLE) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

    }

    void handleContinuationFrame(final ContinuationFrame continuationFrame) {
        // SPEC: 5.1 Stream States
        // Receiving any frame other than HEADERS or PRIORITY on a stream in [IDLE] state MUST be treated as a
        // connection error (Section 5.4.1) of type PROTOCOL_ERROR.
        if (state == State.IDLE) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
        }

//        if (!headerContinuation) {
//            // TODO throw exception because we should have seen a HEADERS frame or a continuation frame
//            //      without END_HEADERS to be here
//        }
//
//        if (continuationFrame.isEndHeaders()) {
//            headerContinuation = false;
//        }

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

    private void sendHeadersFrame(final Http2Headers responseHeaders, boolean endOfStream) throws IOException {
        if (state == State.HALF_CLOSED) {
            // Encode the headers into a packed, compressed set of bytes, and write them to the buffer.
            final var arrays = new ReusableByteArrayOutputStream(responseHeadersFrame.getFieldBlockFragment());
            final var length = connection.getHeaderCodec().encode(responseHeaders, arrays);

            // Now we have an actual length, we can go back and edit a few bytes of the header with the length
            responseHeadersFrame
                    .setStreamId(streamId)
                    .setEndHeaders(true)
                    .setEndStream(endOfStream)
                    .setBlockLength(length);

            final var buf = connection.getOutputBuffer();
            responseHeadersFrame.write(buf);
            connection.sendOutput(buf);
        }
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
            final var buf = connection.getOutputBuffer();
            responseRstStreamFrame.setStreamId(streamId).setErrorCode(Http2ErrorCode.NO_ERROR).write(buf);
            connection.sendOutput(buf);
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
            checkoutBuffer();
            Frame.writeHeader(currentBuffer, 0, FrameType.CONTINUATION, (byte) 0, streamId);
        }

        private void sendRequestOutputBufferContentsAsLastFrame() {
            submitFrame(true);
        }

        private void checkoutBuffer() {
            currentBuffer = connection.getOutputBuffer();
            currentBuffer.setOnDataFullCallback(this::sendRequestOutputBufferContentsAsLastFrame);
            currentBuffer.setOnCloseCallback(this::sendRequestOutputBufferContentsAsFrame);
        }

        private void submitFrame(boolean endOfStream) {
            final var size = currentBuffer.size();
            currentBuffer.position(0);
            currentBuffer.write24BitInteger(size - Frame.FRAME_HEADER_SIZE);
            currentBuffer.position(4);
            currentBuffer.writeByte(endOfStream ? 1 : 0);
            currentBuffer.position(size);
            connection.sendOutput(currentBuffer);
            currentBuffer = null;
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
