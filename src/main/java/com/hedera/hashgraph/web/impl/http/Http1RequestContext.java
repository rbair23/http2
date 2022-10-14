package com.hedera.hashgraph.web.impl.http;

import com.hedera.hashgraph.web.*;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.session.RequestContext;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.GZIPOutputStream;

import static com.hedera.hashgraph.web.impl.http.Http1Constants.*;

/**
 * Handles collecting all data for a request and response along with sending the response.
 */
class Http1RequestContext extends RequestContext {
    private final OutputBuffer outputBuffer;

    private final Runnable responseSentCallback;
    private BodyInputStream requestBody;
    private SocketChannel channel;

    /**
     * Create a new instance.
     *
     * @param dispatcher           The dispatcher to send this request to. Must not be null.
     * @param outputBuffer
     * @param responseSentCallback
     */
    protected Http1RequestContext(Dispatcher dispatcher, OutputBuffer outputBuffer, Runnable responseSentCallback) {
        super(dispatcher);
        this.outputBuffer = outputBuffer;
        this.responseSentCallback = responseSentCallback;
    }

    public void setChannel(SocketChannel channel) {
        this.channel = channel;
    }

    public void setRequestBody(BodyInputStream requestBody) {
        this.requestBody = requestBody;
    }

    @Override
    public InputStream getRequestBody() {
        return requestBody;
    }

    protected void dispatch() {
        dispatcher.dispatch(this);
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public void setVersion(HttpVersion version) {
        this.version = version;
    }

    public void setResponseHeaders(WebHeaders responseHeaders) {
        this.responseHeaders = responseHeaders;
    }

    public void setResponseCode(StatusCode responseCode) {
        this.responseCode = responseCode;
    }

    private void responseSent() {
        responseSentCallback.run();
    }

    void respond(StatusCode statusCode, WebHeaders responseHeaders, String plainTextBody)
            throws ResponseAlreadySentException {
        try {
            responseCode = statusCode;
            this.responseHeaders = responseHeaders;
            byte[] contentBytes = plainTextBody.getBytes(StandardCharsets.US_ASCII);
            responseHeaders.setContentLength(contentBytes.length);
            responseHeaders.setContentType("text/plain");
            sendHeader();
            // send body
            outputBuffer.reset();
            outputBuffer.write(contentBytes);
            outputBuffer.write(CR);
            outputBuffer.write(LF);
            outputBuffer.sendContentsToChannel(channel);
        } catch (IOException e) {
            e.printStackTrace(); // TODO not sure here
        } finally {
            responseSent();
        }
    }

    private void sendHeader() throws IOException {
        // Add standard response headers
        responseHeaders.setStandardResponseHeaders();
        // SEND DATA
        outputBuffer.reset();
        // send response line
        outputBuffer.write(version.versionString());
        outputBuffer.write(SPACE);
        outputBuffer.write(Integer.toString(responseCode.code()));
        outputBuffer.write(SPACE);
        outputBuffer.write(responseCode.message());
        outputBuffer.write(CR);
        outputBuffer.write(LF);
        // send headers
        responseHeaders.forEach((key, value) -> {
            outputBuffer.write(key);
            outputBuffer.write(COLON);
            outputBuffer.write(SPACE);
            outputBuffer.write(value);
            outputBuffer.write(CR);
            outputBuffer.write(LF);
        });
        outputBuffer.write(CR);
        outputBuffer.write(LF);
        outputBuffer.sendContentsToChannel(channel);
    }

    // =================================================================================================================
    // WebRequest Methods

    @Override
    public OutputStream startResponse(StatusCode statusCode, WebHeaders responseHeaders) throws ResponseAlreadySentException {
        try {
            this.responseCode = Objects.requireNonNull(statusCode);
            this.responseHeaders = Objects.requireNonNull(responseHeaders);
            sendHeader();
            // create low level sending stream
            final HttpOutputStream httpOutputStream = new HttpOutputStream(outputBuffer, channel){
                @Override
                public void close() throws IOException {
                    super.close();
                    channel.write(ByteBuffer.wrap(new byte[]{CR,LF}));
                    responseSent();
                }
            };
            OutputStream outputStream = httpOutputStream;
            // check for gzip
            final String contentEncoding = this.responseHeaders.getContentEncoding();
            if (contentEncoding != null && contentEncoding.contains(WebHeaders.CONTENT_ENCODING_GZIP)) {
                outputStream = new GZIPOutputStream(outputStream);
            }
            return outputStream;
        } catch (IOException e) {
            e.printStackTrace(); // TODO not sure here
            throw new RuntimeException(e);
        }
    }

    @Override
    public void respond(StatusCode statusCode, WebHeaders responseHeaders) throws ResponseAlreadySentException {
        respond(statusCode, responseHeaders, statusCode.code()+" - "+statusCode.message());
    }

    @Override
    public void respond(StatusCode statusCode) throws ResponseAlreadySentException {
        respond(statusCode, new WebHeaders(), statusCode.code()+" - "+statusCode.message());
    }
}
