package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.ResponseAlreadySentException;
import com.hedera.hashgraph.web.StatusCode;
import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.HttpVersion;
import com.hedera.hashgraph.web.impl.http2.frames.DataFrame;
import com.hedera.hashgraph.web.impl.http2.frames.HeadersFrame;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;
import com.hedera.hashgraph.web.impl.session.RequestContext;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import com.hedera.hashgraph.web.impl.util.ReusableByteArrayInputStream;
import com.twitter.hpack.Decoder;
import com.twitter.hpack.Encoder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class Http2RequestContext extends RequestContext {
    /**
     * The different states supported by the HTTP/2.0 Stream States state machine.
     * See the specification, Figure 2, and in section 5.1
     */
    private enum State {
        IDLE,
        OPEN,
        HALF_CLOSED,
        CLOSED,
        RESERVED
    }

    /**
     * The HTTP/2.0 stream ID associated with this request
     */
    private int streamId;

    /**
     * The current state of this stream.
     */
    private State state = State.IDLE;

    /**
     * A callback to be invoked when the {@link #close()} method is called.
     */
    private IntConsumer onClose;

    // TODO I will need the client settings


    private final ContextReuseManager contextReuseManager;


    /**
     * TODO I don't think we should need this anymore?
     */
    protected final byte[] requestBody;

    /**
     * TODO I don't think we should need this anymore?
     */
    private int requestBodyLength = 0;

    /**
     * TODO I don't think we should need this anymore?
     */
    protected final ReusableByteArrayInputStream requestBodyInputStream;


    // TODO Could reuse requestBody byte[] here but that would mean we need semantics for when you can and can't ready body, ugg
    private final OutputBuffer dataOutputBuffer = new OutputBuffer(
            16*1024, // 16K
            this::sendRequestOutputBufferContentsAsLastFrame, // when use closes stream send data as frame
            this::sendRequestOutputBufferContentsAsFrame);  // if the user write too much data then send in multiple frames


    /**
     * Separate buffer for frame header
     */
    private final OutputBuffer frameHeaderBuffer = new OutputBuffer(1024);

    private final ByteBuffer[] headerAndDataBuffers = new ByteBuffer[]{frameHeaderBuffer.getBuffer(), dataOutputBuffer.getBuffer()};

    // =================================================================================================================
    // Constructor & Methods

    /**
     * Create a new instance.
     *
     * @param contextReuseManager The {@link ContextReuseManager} that manages this instance. Must not be null.
     * @param dispatcher The {@link Dispatcher} to use for dispatching requests. Cannot be null.
     */
    public Http2RequestContext(final ContextReuseManager contextReuseManager, final Dispatcher dispatcher) {
        super(dispatcher);
        this.contextReuseManager = Objects.requireNonNull(contextReuseManager);

        this.requestBody = new byte[16*1024];
        this.requestBodyInputStream = new ReusableByteArrayInputStream(requestBody);
    }

    /**
     * Resets the instance prior to use.
     *
     * @param onClose A callback to invoke when the context is closed
     */
    public void reset(IntConsumer onClose, SocketChannel channel) {
        super.reset();
//        this.channel = channel; // kinda suspicious...
        this.state = State.IDLE;
        this.streamId = -1;
        this.onClose = onClose;
        this.dataOutputBuffer.reset();
        this.frameHeaderBuffer.reset();

        requestBodyLength = 0;
        requestBodyInputStream.reuse(0);
    }

    private void sendRequestOutputBufferContentsAsLastFrame() {
        try {
            // Create Frame Header
            frameHeaderBuffer.reset();
            DataFrame.writeHeader(frameHeaderBuffer, streamId, dataOutputBuffer.size());
            DataFrame.writeLastHeader(frameHeaderBuffer, streamId);
            sendFrame();
        } catch (IOException fatalToConnection) {
            fatalToConnection.printStackTrace();
        }
    }

    private void sendRequestOutputBufferContentsAsFrame() {
        try {
            // Create Frame Header
            frameHeaderBuffer.reset();
            DataFrame.writeHeader(frameHeaderBuffer, streamId, dataOutputBuffer.size());
            sendFrame();
        } catch (IOException fatalToConnection) {
            fatalToConnection.printStackTrace();
        }
    }

    private void sendFrame() throws IOException {
        // flip both buffers ready to write
        frameHeaderBuffer.getBuffer().flip();
        dataOutputBuffer.getBuffer().flip();
        // write both buffers atomically to channel
        // TODO channel.write(headerAndDataBuffers);
    }

    // =================================================================================================================
    // WebRequest Methods

    @Override
    public OutputStream startResponse(StatusCode statusCode, WebHeaders responseHeaders) throws ResponseAlreadySentException {
        this.responseCode = statusCode;
        this.responseHeaders = responseHeaders;
        try {
            sendHeaders();
        } catch (IOException fatalToConnection) {
            fatalToConnection.printStackTrace();
        }
        dataOutputBuffer.reset();
        return dataOutputBuffer; // TODO output buffer needs a call back for when buffer is full and when it is closed
    }

    @Override
    public void respond(StatusCode statusCode, WebHeaders responseHeaders) throws ResponseAlreadySentException {
        this.responseCode = statusCode;
        this.responseHeaders = responseHeaders;
        try {
            sendHeaders();
        } catch (IOException fatalToConnection) {
            fatalToConnection.printStackTrace();
        }
    }

    @Override
    public void respond(StatusCode statusCode) throws ResponseAlreadySentException {
        this.responseCode = statusCode;
        this.responseHeaders = new WebHeaders();
        try {
            sendHeaders();
        } catch (IOException fatalToConnection) {
            fatalToConnection.printStackTrace();
        }
    }

    private void sendHeaders() throws IOException {
        // write encoded headers to temp buffer as we need to know the length before we can build the frame
//        final var encoder = new Encoder((int) connectionContext.clientSettings.getHeaderTableSize());
//        dataOutputBuffer.reset();
//        encoder.encodeHeader(dataOutputBuffer, ":status".getBytes(), ("" + responseCode).getBytes(), false);
//        final AtomicReference<IOException> ioException = new AtomicReference<>();
//        responseHeaders.forEach((k, v) -> {
//            try {
//                encoder.encodeHeader(dataOutputBuffer, k.getBytes(), v.getBytes(), false);
//            } catch (IOException e) {
//                ioException.set(e);
//            }
//        });
//        final var e = ioException.get();
//        if (e != null) {
//            throw e;
//        } else {
//            frameHeaderBuffer.reset();
//            HeadersFrame.writeHeader(frameHeaderBuffer, requestId, dataOutputBuffer.size());
//            sendFrame();
//        }
    }

    public void handleHeaders(HeadersFrame headerFrame) {
        // set the request id, and get all the header data decoded, and so forth.
        streamId = headerFrame.getStreamId();
//        if (headerFrame.isCompleteHeader()) {
//            // I have all the bytes I will need... so I can go and decode them
//            final var decoder = new Decoder(
//                    (int) serverSettings.getMaxHeaderListSize(),
//                    (int) serverSettings.getHeaderTableSize());
//
//            final var headers = new WebHeaders();
//            decoder.decode(new ByteArrayInputStream(headerFrame.getFieldBlockFragment()), (name, value, sensitive) -> {
//                // sensitive is a boolean
//                headers.put(new String(name), new String(value));
//            });
//            requestSession.setHeaders(headers);
//        }
//
//        if (headerFrame.isEndStream()) {
//            state = Http2ConnectionContext.State.READY_FOR_DISPATCH;
//        }

        /*
                        // Go forth and handle. Good luck.
                        final var requestHeaders = requestSession.getHeaders();
                        requestSession.setPath(requestHeaders.get(":path"));
                        requestSession.setMethod(requestHeaders.get(":method"));
                        requestSession.setVersion("HTTP/2.0");
                        final var webRequest = new WebRequestImpl(requestSession, );
                        doDispatch.accept(channelSession, webRequest);
                        state = State.DISPATCHING;
                        return;

         */
    }

    //@Override TODO
    public void close() {
        // super.close(); TODO
        if (onClose != null) {
            onClose.accept(streamId);
        }
        streamId = -1;
    }

    public void handleRstStream(Http2ErrorCode errorCode) {
        System.out.println("RST_STREAM: " + errorCode.name());
        close();
    }
}
