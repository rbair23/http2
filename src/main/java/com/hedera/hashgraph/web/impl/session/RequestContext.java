package com.hedera.hashgraph.web.impl.session;

import com.hedera.hashgraph.web.HttpVersion;
import com.hedera.hashgraph.web.WebRequest;
import com.hedera.hashgraph.web.impl.Dispatcher;

import java.io.InputStream;
import java.util.Objects;

/**
 * Represents a single request. They double as the implementation of {@link WebRequest}, so we do not
 * have to copy data into the {@link WebRequest}. This means that initially this class is called on the
 * "web server" thread, as it is populated with data, and then switches to one of the "web handler"
 * threads from the thread pool. So multi-threading considerations need to be taken into account.
 */
public abstract class RequestContext implements WebRequest {

    /**
     * The {@link Dispatcher} to use for dispatching {@link com.hedera.hashgraph.web.WebRequest}s.
     */
    protected final Dispatcher dispatcher;

    // =================================================================================================================
    // Request Data

    /**
     * This field is set while parsing the HTTP request, and before the request is sent to a handler.
     */
    protected String method;

    /**
     * This field is set while parsing the HTTP request, and before the request is sent to a handler.
     */
    protected String path;

    /**
     * This field is set while parsing the HTTP request, and before the request is sent to a handler.
     */
    protected final HttpVersion version;

    // =================================================================================================================
    // ConnectionContext Methods

    /**
     * Create a new instance.
     *
     * @param dispatcher The dispatcher to send this request to. Must not be null.
     */
    protected RequestContext(final Dispatcher dispatcher, final HttpVersion version) {
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.version = version;
    }

    /**
     * Called to init the request context prior to its next use.
     */
    protected void reset() {
        method = null;
        path = null;
    }

    // =================================================================================================================
    // WebRequest Methods

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
    public InputStream getRequestBody() {
        // TODO This needs to be implemented!!!
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