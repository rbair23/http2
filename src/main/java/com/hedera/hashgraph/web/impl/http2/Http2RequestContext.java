package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.ResponseAlreadySentException;
import com.hedera.hashgraph.web.StatusCode;
import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.HttpVersion;
import com.hedera.hashgraph.web.impl.http2.frames.DataFrame;
import com.hedera.hashgraph.web.impl.http2.frames.HeadersFrame;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;
import com.hedera.hashgraph.web.impl.session.RequestContext;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import com.twitter.hpack.Encoder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class Http2RequestContext extends RequestContext {
    private int requestId;

    private Http2ConnectionContext connectionContext;

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

    public Http2RequestContext(ContextReuseManager contextReuseManager, Dispatcher dispatcher) {
        super(contextReuseManager, dispatcher);
    }

    Http2RequestContext reset(Http2ConnectionContext connectionContext, int requestId) {
        super.reset();
        this.requestId = requestId;
        this.connectionContext = connectionContext;
        this.dataOutputBuffer.reset();
        this.frameHeaderBuffer.reset();
        return this;
    }

    private void sendRequestOutputBufferContentsAsLastFrame() {
        try {
            // Create Frame Header
            frameHeaderBuffer.reset();
            DataFrame.writeHeader(frameHeaderBuffer, requestId, dataOutputBuffer.size());
            DataFrame.writeLastHeader(frameHeaderBuffer, requestId);
            sendFrame();
        } catch (IOException fatalToConnection) {
            fatalToConnection.printStackTrace();
        }
    }

    private void sendRequestOutputBufferContentsAsFrame() {
        try {
            // Create Frame Header
            frameHeaderBuffer.reset();
            DataFrame.writeHeader(frameHeaderBuffer, requestId, dataOutputBuffer.size());
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
        channel.write(headerAndDataBuffers);
    }

    // =================================================================================================================
    // RequestContext Methods

    // TODO not sure the meaning of this for Http2RequestContext
    @Override
    public void handle(Consumer<HttpVersion> upgradeConnectionCallback) {}

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
        final var encoder = new Encoder((int) connectionContext.clientSettings.getHeaderTableSize());
        dataOutputBuffer.reset();
        encoder.encodeHeader(dataOutputBuffer, ":status".getBytes(), ("" + responseCode).getBytes(), false);
        final AtomicReference<IOException> ioException = new AtomicReference<>();
        responseHeaders.forEach((k, v) -> {
            try {
                encoder.encodeHeader(dataOutputBuffer, k.getBytes(), v.getBytes(), false);
            } catch (IOException e) {
                ioException.set(e);
            }
        });
        final var e = ioException.get();
        if (e != null) {
            throw e;
        } else {
            frameHeaderBuffer.reset();
            HeadersFrame.writeHeader(frameHeaderBuffer, requestId, dataOutputBuffer.size());
            sendFrame();
        }
    }
}
