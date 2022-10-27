package com.hedera.hashgraph.web.impl.http;

import com.hedera.hashgraph.web.*;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.WebHeadersImpl;
import com.hedera.hashgraph.web.impl.session.RequestContext;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

import static com.hedera.hashgraph.web.impl.http.Http1Constants.*;

/**
 * Handles collecting all data for a request and response along with sending the response.
 */
class Http1RequestResponseContext extends RequestContext implements WebRequest, WebResponse {
    private final Consumer<OutputBuffer> sendResponse;
    private final Runnable sendingComplete;
    private final Supplier<OutputBuffer> checkoutOutputBuffer;
    private BodyInputStream requestBody;

    // =================================================================================================================
    // Request Data

    /**
     * This field is set while parsing the HTTP request, and before the request is sent to a handler.
     */
    private String method;

    /**
     * This field is set while parsing the HTTP request, and before the request is sent to a handler.
     */
    private String path;

    /**
     * The request headers. This instance is reused between requests. After we read all the header data,
     * we parse it and set the headers in this the {@link WebHeaders} instance.
     */
    private final WebHeadersImpl requestHeaders = new WebHeadersImpl();


    private final WebHeadersImpl responseHeaders = new WebHeadersImpl();
    private StatusCode responseStatusCode = null;
    private boolean respondHasBeenCalled = false;


    /**
     * Create a new instance.
     *
     * @param dispatcher           The dispatcher to send this request to. Must not be null.
     * @param checkoutOutputBuffer Supplier to check out reusable output buffers
     * @param sendResponse         Call back to send data buffers to client
     * @param sendingComplete      Call back for when sending of response is complete
     */
    protected Http1RequestResponseContext(Dispatcher dispatcher, Supplier<OutputBuffer> checkoutOutputBuffer,
                                          Consumer<OutputBuffer> sendResponse, Runnable sendingComplete) {
        super(dispatcher, HttpVersion.HTTP_1_1);
        this.checkoutOutputBuffer = checkoutOutputBuffer;
        this.sendResponse = sendResponse;
        this.sendingComplete = sendingComplete;
    }

    // =================================================================================================================
    // Methods used by Http1ConnectionContext

    /**
     * Called by Http1ConnectionContext to set the method as it parses request
     */
    void setMethod(String method) {
        this.method = method;
    }

    /**
     * Called by Http1ConnectionContext to set the path as it parses request
     */
    void setPath(String path) {
        this.path = path;
    }

    /**
     * Called by Http1ConnectionContext to set the http version as it parses request
     */
    void setVersion(HttpVersion version) {
        this.version = version;
    }

    /**
     * Called by Http1ConnectionContext to set the request body input stream as it parses request
     */
    void setRequestBody(BodyInputStream requestBody) {
        this.requestBody = requestBody;
    }

    /**
     * @return true if respond method has been called
     */
    boolean responseHasBeenSent() {
        return respondHasBeenCalled;
    }

    // =================================================================================================================
    // RequestContext & WebRequest Methods

    /**
     * Get the request headers, this always gives direct read/writable reference to headers.
     *
     * @return the request headers
     */
    @Override
    public WebHeaders getRequestHeaders() {
        return requestHeaders;
    }

    /**
     * Does nothing, as Http1ConnectionContext has a single Http1RequestResponseContext that is reused over and over
     */
    @Override
    public void close() {}

    /**
     * Reset this context back to initial state, so it can be reused
     */
    @Override
    protected void reset() {
        super.reset();
        method = null;
        path = null;
        this.requestHeaders.clear();
        this.responseHeaders.clear();
        this.responseStatusCode = null;
        this.respondHasBeenCalled = false;
    }

    @Override
    public String getMethod() {
        return method;
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public HttpVersion getVersion() {
        return version;
    }

    /**
     * Used by the handler to read request body as an input stream. Multiple calls will get the same input stream
     * instance with same state.
     *
     * @return input stream over request body
     */
    @Override
    public InputStream getRequestBody() {
        return requestBody;
    }

    // =================================================================================================================
    // WebResponse Methods

    @Override
    public WebHeaders getHeaders() {
        if (respondHasBeenCalled) {
            throw new IllegalStateException("You can not set response data after respond() has been called.");
        }
        return responseHeaders;
    }

    @Override
    public WebResponse statusCode(StatusCode code) {
        if (respondHasBeenCalled) {
            throw new IllegalStateException("You can not set response data after respond() has been called.");
        }
        this.responseStatusCode = code;
        return this;
    }

    @Override
    public void respond(StatusCode code) {
        if (respondHasBeenCalled) {
            throw new IllegalStateException("You can call respond() after respond() has already been called.");
        }
        this.responseStatusCode = code;
        respond(CONTENT_TYPE_HTML_TEXT, code.code() + " : " + code.message(), StandardCharsets.US_ASCII);
    }

    @Override
    public void respond(String contentType, String bodyAsString, Charset charset) {
        if (respondHasBeenCalled) {
            throw new IllegalStateException("You can call respond() after respond() has already been called.");
        }
        respond(contentType, Objects.requireNonNull(bodyAsString).getBytes(charset));
    }

    @Override
    public void respond(String contentType, byte[] bodyAsBytes) {
        if (respondHasBeenCalled) {
            throw new IllegalStateException("You can call respond() after respond() has already been called.");
        }
        respondHasBeenCalled = true;
        responseHeaders.setContentType(contentType);
        responseHeaders.setContentLength(bodyAsBytes.length);
        if (responseStatusCode == null) responseStatusCode = StatusCode.OK_200;
        // send response
        OutputBuffer outputBuffer = checkoutOutputBuffer.get();
        writeResponseStatusLineAndHeaders(outputBuffer);
        // write as much of the body bytes into buffer as will fit, then send and get new buffer if needed
        int bytesToBeSent = bodyAsBytes.length;
        while (bytesToBeSent > 0) {
            final int bytesThatCanBeSent = Math.min(outputBuffer.remaining(), bytesToBeSent);
            final int startOffset = bodyAsBytes.length - bytesThatCanBeSent;
            outputBuffer.write(bodyAsBytes,startOffset,bytesThatCanBeSent);
            bytesToBeSent -= bytesThatCanBeSent;
            // check if buffer is full
            if (outputBuffer.remaining() == 0) {
                sendResponse.accept(outputBuffer);
                outputBuffer = checkoutOutputBuffer.get();
            }
        }
        // check if we have enough room in buffer for CRLF
        if (outputBuffer.remaining() < 2) {
            sendResponse.accept(outputBuffer);
            outputBuffer = checkoutOutputBuffer.get();
        }
        // add CRLF and send
        outputBuffer.write(CR);
        outputBuffer.write(LF);
        sendResponse.accept(outputBuffer);
        // mark as response sent
        sendingComplete.run();
    }

    @Override
    public OutputStream respond(String contentType) throws IOException {
        if (respondHasBeenCalled) {
            throw new IllegalStateException("You can call respond() after respond() has already been called.");
        }
        // check if content length header has been set
        if (responseHeaders.getContentLength() > 0) {
            return respond(contentType, responseHeaders.getContentLength());
        }
        respondHasBeenCalled = true;
        // TODO needs to collect all content or can send directly if chunked encoding is supported
        return null;
    }

    @Override
    public OutputStream respond(String contentType, int contentLength) throws IOException {
        if (respondHasBeenCalled) {
            throw new IllegalStateException("You can call respond() after respond() has already been called.");
        }
        respondHasBeenCalled = true;
        responseHeaders.setContentType(contentType);
        responseHeaders.setContentLength(contentLength);
        if (responseStatusCode == null) responseStatusCode = StatusCode.OK_200;
        sendResponseStatusLineAndHeaders();
        // create low level sending stream
        OutputStream outputStream = new OutputBufferOutputStream(
                checkoutOutputBuffer, sendResponse, this::responseOutputStreamClosed);
        // check for gzip
        final String contentEncoding = responseHeaders.getContentEncoding();
        if (contentEncoding != null && contentEncoding.contains(WebHeaders.CONTENT_ENCODING_GZIP)) {
            outputStream = new GZIPOutputStream(outputStream);
        }
        return outputStream;
    }

    // =================================================================================================================
    // Implementation

    /**
     * Called to send the initial HTTP status line and headers
     */
    private void sendResponseStatusLineAndHeaders() {
        final OutputBuffer outputBuffer = checkoutOutputBuffer.get();
        writeResponseStatusLineAndHeaders(outputBuffer);
        sendResponse.accept(outputBuffer);
    }

    /**
     * Called to write the initial HTTP status line and headers into a output buffer
     */
    private void writeResponseStatusLineAndHeaders(OutputBuffer outputBuffer) {
        final StatusCode statusCode =
                this.responseStatusCode == null ? StatusCode.INTERNAL_SERVER_ERROR_500 : this.responseStatusCode;
        // Add standard response headers
        responseHeaders.setStandardResponseHeaders();
        // SEND DATA
        // send response line
        outputBuffer.write(version.versionString());
        outputBuffer.write(SPACE);
        outputBuffer.write(Integer.toString(statusCode.code()));
        outputBuffer.write(SPACE);
        outputBuffer.write(statusCode.message());
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
    }

    /**
     * Called when the response output stream is closed, after the body has been sent
     */
    private void responseOutputStreamClosed() {
        // send closing CRLF
        final OutputBuffer outputBuffer = checkoutOutputBuffer.get();
        outputBuffer.write(new byte[]{CR,LF});
        sendResponse.accept(outputBuffer);
        // mark as response sent
        sendingComplete.run();
    }

    @Override
    public String toString() {
        return "Http1RequestResponseContext{" +
                method +" "+path+" " +version.versionString()+
                ", respondHasBeenCalled=" + respondHasBeenCalled +
                ", requestHeaders=" + requestHeaders +
                '}';
    }
}
