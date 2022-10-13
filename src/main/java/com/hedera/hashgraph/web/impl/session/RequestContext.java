package com.hedera.hashgraph.web.impl.session;

import com.hedera.hashgraph.web.HttpVersion;
import com.hedera.hashgraph.web.StatusCode;
import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.WebRequest;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.util.ReusableByteArrayInputStream;

import java.io.IOException;
import java.io.InputStream;

public abstract class RequestContext extends ConnectionContext implements WebRequest {

    // =================================================================================================================
    // Request Data

    /**
     * Parsed from the input stream.
     */
    protected String method;

    /**
     * Parsed from the input stream.
     */
    protected String path;

    /**
     * Parsed from the input stream.
     */
    protected HttpVersion version;

    /**
     * A new instance is created for each request. After we read all the header data, we parse it
     * and produce an instance of {@link WebHeaders}.
     */
    protected final WebHeaders requestHeaders = new WebHeaders();

    protected final byte[] requestBody = new byte[16*1024];
    private int requestBodyLength = 0;
    protected final ReusableByteArrayInputStream requestBodyInputStream = new ReusableByteArrayInputStream(requestBody);

    // =================================================================================================================
    // Response Data
    protected WebHeaders responseHeaders;

    protected StatusCode responseCode;

    // =================================================================================================================
    // ConnectionContext Methods

    protected RequestContext(ContextReuseManager contextReuseManager, Dispatcher dispatcher) {
        super(contextReuseManager, dispatcher, 16*1024);
    }

    @Override
    protected void reset() {
        super.reset();
        requestHeaders.clear();
        requestBodyLength = 0;
        requestBodyInputStream.reuse(0);
        method = null;
        path = null;
        version = null;
        responseHeaders = null;
    }

    public int getRequestBodyLength() {
        return requestBodyLength;
    }

    public void setRequestBodyLength(int requestBodyLength) {
        this.requestBodyLength = requestBodyLength;
        requestBodyInputStream.reuse(requestBodyLength);
    }

    // =================================================================================================================
    // WebRequest Methods

    @Override
    public WebHeaders getRequestHeaders() {
        return requestHeaders;
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

    @Override
    public InputStream getRequestBody() throws IOException {
        return null;
    }

}


/*

    @Override
    public InputStream getRequestBody() throws IOException {
        final var inputStream = new ByteArrayInputStream(requestSession.getData(), 0, requestSession.getDataLength());
        final var headers = requestSession.getHeaders();
        final var contentEncoding = headers.getContentEncoding();
        if (contentEncoding != null && contentEncoding.contains(WebHeaders.CONTENT_ENCODING_GZIP)) {
            return new GZIPInputStream(inputStream);
        } else {
            return inputStream;
        }
    }

    @Override
    public void respond(WebHeaders responseHeaders, int responseCode) throws IOException {
        if (this.responseHeaders != null) {
            // Somebody is trying to respond twice!!
            throw new IOException("Cannot respond twice to the same request");
        }

        this.responseHeaders = Objects.requireNonNull(responseHeaders);
        this.responseCode = responseCode;
    }

    @Override
    public OutputStream startResponse(WebHeaders responseHeaders, int responseCode) throws IOException {
        this.respond(responseHeaders, responseCode);
        requestSession.setDataLength(0);
        outputStream = new RequestDataOutputStream(requestSession);
        final var contentEncoding = responseHeaders.getContentEncoding();
        if (contentEncoding != null && contentEncoding.contains(WebHeaders.CONTENT_ENCODING_GZIP)) {
            return new GZIPOutputStream(outputStream);
        } else {
            return outputStream;
        }
    }
 */