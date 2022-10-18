package com.hedera.hashgraph.web.impl.http;

import com.hedera.hashgraph.web.*;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.WebHeadersImpl;
import com.hedera.hashgraph.web.impl.session.RequestContext;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.ByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.hedera.hashgraph.web.impl.http.Http1Constants.*;

/**
 * Handles collecting all data for a request and response along with sending the response.
 */
class Http1RequestContext extends RequestContext {
    private final Consumer<OutputBuffer> sendResponse;
    private final Supplier<OutputBuffer> checkoutOutputBuffer;
    private BodyInputStream requestBody;
    private ByteChannel channel;

    /**
     * The request headers. This instance is reused between requests. After we read all the header data,
     * we parse it and set the headers in this the {@link WebHeaders} instance.
     */
    private final WebHeadersImpl requestHeaders = new WebHeadersImpl();

    /**
     * Create a new instance.
     *
     * @param dispatcher           The dispatcher to send this request to. Must not be null.
     * @param sendResponse
     */
    protected Http1RequestContext(Dispatcher dispatcher, Supplier<OutputBuffer> checkoutOutputBuffer, 
                                  Consumer<OutputBuffer> sendResponse) {
        super(dispatcher);
        this.checkoutOutputBuffer = checkoutOutputBuffer;
        this.sendResponse = sendResponse;
    }

    @Override
    protected void reset() {
        super.reset();
        requestHeaders.clear();
    }

    public void setChannel(ByteChannel channel) {
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


    void respond(StatusCode statusCode, WebHeaders responseHeaders, String plainTextBody)
            throws ResponseAlreadySentException {
        try {
            byte[] contentBytes = plainTextBody.getBytes(StandardCharsets.US_ASCII);
            responseHeaders.setContentLength(contentBytes.length);
            responseHeaders.setContentType("text/plain");
            sendHeader(statusCode, responseHeaders);
            // send body
            final OutputBuffer outputBuffer = checkoutOutputBuffer.get();
            outputBuffer.write(contentBytes);
            outputBuffer.write(CR);
            outputBuffer.write(LF);
            sendResponse.accept(outputBuffer);
        } catch (IOException e) {
            e.printStackTrace(); // TODO not sure here
            throw new RuntimeException(e);
        }
    }

    private void sendHeader(StatusCode statusCode, WebHeaders responseHeaders) throws IOException {
        // Add standard response headers
        responseHeaders.setStandardResponseHeaders();
        // SEND DATA
        final OutputBuffer outputBuffer = checkoutOutputBuffer.get();
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
        sendResponse.accept(outputBuffer);
    }

    // =================================================================================================================
    // WebRequest Methods

    @Override
    public WebHeaders getRequestHeaders() {
        return requestHeaders;
    }

//    @Override
//    public OutputStream startResponse(StatusCode statusCode, WebHeaders responseHeaders) throws ResponseAlreadySentException {
//        try {
//            sendHeader(statusCode, responseHeaders);
//            // create low level sending stream
//            final HttpOutputStream httpOutputStream = new HttpOutputStream(outputBuffer, channel){
//                @Override
//                public void close() throws IOException {
//                    super.close();
//                    channel.write(ByteBuffer.wrap(new byte[]{CR,LF}));
//                    responseSent();
//                }
//            };
//            OutputStream outputStream = httpOutputStream;
//            // check for gzip
//            final String contentEncoding = responseHeaders.getContentEncoding();
//            if (contentEncoding != null && contentEncoding.contains(WebHeaders.CONTENT_ENCODING_GZIP)) {
//                outputStream = new GZIPOutputStream(outputStream);
//            }
//            return outputStream;
//        } catch (IOException e) {
//            e.printStackTrace(); // TODO not sure here
//            throw new RuntimeException(e);
//        }
//    }

//    @Override
//    public void respond(StatusCode statusCode, WebHeaders responseHeaders) throws ResponseAlreadySentException {
//        respond(statusCode, responseHeaders, statusCode.code()+" - "+statusCode.message());
//    }

    @Override
    public void setResponseStatusCode(StatusCode statusCode) throws ResponseAlreadySentException {
        respond(statusCode, new WebHeadersImpl(), statusCode.code()+" - "+statusCode.message());
    }

    @Override
    public WebResponse getResponse() throws ResponseAlreadySentException {
        return new WebResponse() {
            @Override
            public WebHeaders getHeaders() {
                return null;
            }

            @Override
            public WebResponse statusCode(StatusCode code) {
                return null;
            }

            @Override
            public WebResponse body(String bodyAsString) throws ResponseAlreadySentException {
                return null;
            }

            @Override
            public WebResponse body(byte[] bodyAsBytes) throws ResponseAlreadySentException {
                return null;
            }

            @Override
            public OutputStream body() throws ResponseAlreadySentException, IOException {
                return null;
            }
        };
    }

    @Override
    public void close() throws Exception {

    }

    private class Http1WebResponse implements WebResponse {
        private final WebHeaders headers = new WebHeadersImpl();

        @Override
        public WebHeaders getHeaders() {
            return headers;
        }

        @Override
        public WebResponse statusCode(StatusCode code) {
            return null;
        }

        @Override
        public WebResponse body(String bodyAsString) throws ResponseAlreadySentException {
            return null;
        }

        @Override
        public WebResponse body(byte[] bodyAsBytes) throws ResponseAlreadySentException {
            return null;
        }

        @Override
        public OutputStream body() throws ResponseAlreadySentException, IOException {
            return null;
        }
    }
}
