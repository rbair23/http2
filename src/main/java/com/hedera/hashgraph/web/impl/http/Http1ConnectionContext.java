package com.hedera.hashgraph.web.impl.http;

import com.hedera.hashgraph.web.HttpVersion;
import com.hedera.hashgraph.web.StatusCode;
import com.hedera.hashgraph.web.WebResponse;
import com.hedera.hashgraph.web.WebServerConfig;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.session.ConnectionContext;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.ByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static com.hedera.hashgraph.web.impl.http.Http1Constants.*;


/**
 * The HTTP 1.1 spec is vast, there are a number of things not supported here. Bellow is a list of them:
 * <ul>
 *     <li><a href="https://httpwg.org/specs/rfc9110.html#trailer.fields">Trailer Fields</a></li>
 * </ul>
 * There is two ways of doing multiple requests with HTTP 1.1:
 * <ul>
 *     <li>One complete request response followed by another.</li>
 *     <li>One stream of requests and one of responses. As long as responses are in same order as requests. This is
 *     called "Pipelining" in the spec and seems to have been abandoned so we will only support the first mode.
 *     <a href="https://en.m.wikipedia.org/wiki/HTTP_pipelining"></a></li>
 * </ul>
 */
@SuppressWarnings("SameParameterValue")
public class Http1ConnectionContext extends ConnectionContext {

    /** States for parser state machine */
    private enum State {
        BEGIN,
        METHOD,
        URI,
        VERSION,
        HTTP2_PREFACE,
        HEADER_KEY,
        HEADER_VALUE,
        WAITING_FOR_END_OF_REQUEST_BODY,
        WAITING_FOR_RESPONSE_TO_BE_SENT,
        HTTP2_UPGRADE
    };

    /** Parsing state machine current state */
    private State state;

    /** Temporary string used during parsing to hold a header key until we finish parsing its value */
    private String tempHeaderKey;

    /**
     * Request response context for collecting request data and response data. Can be reused request to request for
     * keep alive HTTP 1.1.
     */
    private final Http1RequestResponseContext requestResponseContext;

    /**
     * The dispatcher for handling incoming web requests
     */
    private final Dispatcher dispatcher;

    /**
     * True when we are in HTTP 1.1 keep alive mode where more than one request is handled by this connection
     */
    private boolean isKeepAlive = false;

    /**
     * This future represents the currently submitted job to the {@link Dispatcher}. When the stream
     * is closed || terminated, then this job will be canceled if it has not been completed.
     * Canceling the job will only cause the associated thread to be interrupted, it is up to the
     * application to respect the {@link InterruptedException} and stop working.
     */
    private Future<?> submittedJob;

    /**
     * Create a new instance.
     *
     * @param contextReuseManager The {@link ContextReuseManager} that manages this instance. Must not be null.
     * @param dispatcher The dispatcher for handling incoming web requests
     * @param config Web server configuration, cannot be null.
     */
    public Http1ConnectionContext(final ContextReuseManager contextReuseManager, final Dispatcher dispatcher,
                                  final WebServerConfig config) {
        super(contextReuseManager, config.outputBufferSize());
        Objects.requireNonNull(contextReuseManager);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        Objects.requireNonNull(config);
        this.requestResponseContext = new Http1RequestResponseContext(dispatcher, contextReuseManager::checkoutOutputBuffer,
                this::sendOutput, this::responseCompletelySent);
    }

    @Override
    public void reset(ByteChannel channel) {
        super.reset(channel);
        state = State.BEGIN;
        tempHeaderKey = null;
        isKeepAlive = false;
        requestResponseContext.reset();
        submittedJob = null;
    }

    @Override
    protected void doHandle(Consumer<HttpVersion> onConnectionUpgrade) {
        //         generic-message = start-line
        //                          *(message-header CRLF)
        //                          CRLF
        //                          [ message-body ]
        //        start-line      = Request-Line | Status-Line
        // loop while there is still data to process, we can go through multiple states

        while (inputBuffer.available(1)) {
                System.out.println("state = " + state);
            switch (state) {
                case BEGIN:
                    inputBuffer.mark();
                    state = State.METHOD;
                    break;
                case METHOD:
                    if (searchForSpace(MAX_METHOD_LENGTH)) {
                        // we found a space, so read between mark and current position as string
                        final int bytesRead = inputBuffer.resetToMark();
                        requestResponseContext.setMethod(inputBuffer.readString(bytesRead, StandardCharsets.US_ASCII));
                        // skip over the space
                        inputBuffer.skip(1);
                        // next state
                        inputBuffer.mark();
                        state = State.URI;
                    }
                    break;
                case URI:
                    if (searchForSpace(MAX_URI_LENGTH)) {
                        try {
                            // we found a space, so read between mark and current position as string
                            final int bytesRead = inputBuffer.resetToMark();
                            final String uriString = inputBuffer.readString(bytesRead, StandardCharsets.US_ASCII);
                            final URI uri = new URI(uriString);
                            requestResponseContext.setPath(uri.getPath());
                            // skip over the space
                            inputBuffer.skip(1);
                            // next state
                            inputBuffer.mark();
                            state = State.VERSION;
                        } catch (URISyntaxException e) {
                            e.printStackTrace();
                            requestResponseContext.statusCode(StatusCode.BAD_REQUEST_400)
                                .respond(WebResponse.CONTENT_TYPE_PLAIN_TEXT,"Bad URI: "+e.getMessage());
                            close();
                        }
                    }
                    break;
                case VERSION:
                    if (searchForEndOfLine(MAX_VERSION_LENGTH)) {
                        try {
                            // we found a space, so read between mark and current position as string
                            final int bytesRead = inputBuffer.resetToMark();
                            final var version = inputBuffer.readVersion();
                            System.out.println("version = " + version);
                            requestResponseContext.setVersion(version);
                            // check for unknown version
                            if (version == null) {
                                closeWithError(StatusCode.HTTP_VERSION_NOT_SUPPORTED_505);
                                return;
                            }
                            // skip over the new line
                            inputBuffer.skip(2);
                            // handle versions
                            switch (version) {
                                case HTTP_1, HTTP_1_1 -> {
                                    // next state
                                    inputBuffer.mark();
                                    state = State.HEADER_KEY;
                                }
                                case HTTP_2 -> {
                                    // next state
                                    inputBuffer.mark();
                                    state = State.HTTP2_PREFACE;
                                }
                            }
//                                System.out.println("----- "+requestContext.getMethod()+"  "+requestContext.getPath()+"  "+requestContext.getVersion());
                        } catch (Exception e) {
                            closeWithError(StatusCode.HTTP_VERSION_NOT_SUPPORTED_505);
                            return;
                        }
                    }
                    break;
                case HTTP2_PREFACE:
                    if (inputBuffer.available(HTTP2_PREFACE_END.length)) {
                        for (final byte b : HTTP2_PREFACE_END) {
                            if (inputBuffer.readByte() != b) {
                                closeWithError(StatusCode.BAD_REQUEST_400);
                                return;
                            }
                        }
                        // full preface read, now hand over to http 2 handler
                        onConnectionUpgrade.accept(HttpVersion.HTTP_2);
                        return;
                    }
                    break;
                case HEADER_KEY:
                    // check for end of headers with a double new line
                    if (inputBuffer.available(2)) {
                        // look ahead for end of line
                        if ((char) inputBuffer.peekByte() == CR) {
                            if ((char) inputBuffer.peekByte(1) == LF) {
                                // we have handled the next to chars so move ahead
                                inputBuffer.skip(2);
                                // we are now ready for body
                                inputBuffer.mark();
                                System.out.println(requestResponseContext);
                                // check keepalive header
                                isKeepAlive = requestResponseContext.getVersion() == HttpVersion.HTTP_1_1 ||
                                        requestResponseContext.getRequestHeaders().getKeepAlive();
                                System.out.println("isKeepAlive = " + isKeepAlive);
                                // handle http2 upgrade header
                                final String connectionHeader = requestResponseContext.getRequestHeaders().get("connection");
                                final String upgradeHeader = requestResponseContext.getRequestHeaders().get("upgrade");
                                if (connectionHeader != null && connectionHeader.toLowerCase().contains("upgrade") &&
                                        upgradeHeader != null && upgradeHeader.toLowerCase().contains("h2c")) {
                                    // TODO half baked h2c upgrade implementation, server sends a header
                                    // TODO HTTP2-Settings, http2-settings: AAEAAEAAAAIAAAABAAMAAABkAAQBAAAAAAUAAEAA
                                    // TODO Need to work out how to pass that to HTTP2 implementation
                                    // TODO see Http1to2UpgradeTest
                                    System.out.println("HTTP2 Upgrade started");
                                    isKeepAlive = true;
                                    state = State.HTTP2_UPGRADE;
                                    requestResponseContext.respond(StatusCode.SWITCHING_PROTOCOLS_101);
                                    // make sure it is sent
                                    handleOutgoingData();
                                    // switching protocols response sent, now hand over to http 2 handler
                                    onConnectionUpgrade.accept(HttpVersion.HTTP_2);
                                    return;
                                }
                                // check headers
//                                    System.out.println("isKeepAlive = " + isKeepAlive);
                                // check if there is a request body to read
                                final int bodySize = requestResponseContext.getRequestHeaders().getContentLength();
//                                    System.out.println("bodySize = " + bodySize);
                                if (bodySize > 0) {
                                    if (inputBuffer.available(bodySize)) {
                                        requestResponseContext.setRequestBody(new BodyInputStream(inputBuffer, bodySize));
                                    } else {
                                        state = State.WAITING_FOR_END_OF_REQUEST_BODY;
                                        return;
                                    }
                                }
                                // dispatch
                                System.out.println(" dispatching");
                                state = State.WAITING_FOR_RESPONSE_TO_BE_SENT;
                                submittedJob = dispatcher.dispatch(requestResponseContext, requestResponseContext);
                                System.out.println(" dispatching done");
                                // we have done our job reading data, now up to dispatcher to send response and the
                                // call callback.
                                return;
                            } else {
                                closeWithError(StatusCode.BAD_REQUEST_400);
                            }
                        }
                    }
                    // now handle normal header key
                    if (searchForHeaderSeparator(MAX_HEADER_KEY_LENGTH)) {
                        // we found a separator, so read between mark and current position as string
                        final int bytesRead = inputBuffer.resetToMark();
                        tempHeaderKey = inputBuffer.readString(bytesRead, StandardCharsets.US_ASCII);
                        // skip over separator
                        inputBuffer.skip(1);
                        // change to header value state
                        inputBuffer.mark();
                        state = State.HEADER_VALUE;
                    }
                    break;
                case HEADER_VALUE:
                    if (searchForEndOfLine(MAX_HEADER_VALUE_LENGTH)) {
                        // we found an end of line, so read between mark and current position as string
                        final int bytesRead = inputBuffer.resetToMark();
                        final String headerValue = inputBuffer.readString(bytesRead, StandardCharsets.US_ASCII)
                                .trim(); // TODO is trim efficient enough and is it too tolerant of white space chars
                        requestResponseContext.getRequestHeaders().put(tempHeaderKey, headerValue);
                        // skip over separator
                        inputBuffer.skip(2);
                        // change to header value state
                        inputBuffer.mark();
                        state = State.HEADER_KEY;
                    }
                    break;
                case WAITING_FOR_END_OF_REQUEST_BODY:
                    final int bodySize = requestResponseContext.getRequestHeaders().getContentLength();
                    if (inputBuffer.available(bodySize)) {
                        requestResponseContext.setRequestBody(new BodyInputStream(inputBuffer,bodySize));
                        // dispatch
                        state = State.WAITING_FOR_RESPONSE_TO_BE_SENT;
                        submittedJob = dispatcher.dispatch(requestResponseContext, requestResponseContext);
                    }
                    break;
                case WAITING_FOR_RESPONSE_TO_BE_SENT :
                    System.out.println("WAITING_FOR_RESPONSE_TO_BE_SENT");
                    // we are not reading any data in this state that might be coming in for next http 1.1 request
                    if (submittedJob.isDone()) {
                        // check if dispatch throw an exception
                        try {
                            submittedJob.get(1, TimeUnit.MILLISECONDS);
                        } catch (ExecutionException e) {
                            if (!requestResponseContext.responseHasBeenSent()) {
                                requestResponseContext.respond(StatusCode.INTERNAL_SERVER_ERROR_500);
                                close();
                            }
                            e.printStackTrace();
                        } catch (InterruptedException ignore) {
                            // this is allowed to happen
                            ignore.printStackTrace();
                        } catch (TimeoutException e) {
                            // should never happen
                            throw new RuntimeException(e);
                        }
                    }
                    break;
            }
        }
    }

    @Override
    public void handleOutgoingData() {
        super.handleOutgoingData();
//        System.out.println("Http1ConnectionContext.handleOutgoingData state="+state+" isKeepAlive="+isKeepAlive+" isOutgoingDataAllSent="+isOutgoingDataAllSent());
        if (state == State.WAITING_FOR_RESPONSE_TO_BE_SENT && isOutgoingDataAllSent()) {
            if (isKeepAlive) {
                state = State.BEGIN;
                tempHeaderKey = null;
                requestResponseContext.reset();
            } else if (isClosed()) {
                // we are done sending data and the connection is closed so no new data can be added to outgoing queue,
                // so we can terminate this connection context.
                terminate();
            }
        }
    }


    /**
     * Helper method to send a simple status code error response.
     */
    private void closeWithError(StatusCode statusCode) {
        if (!requestResponseContext.responseHasBeenSent()) {
            requestResponseContext.respond(statusCode);
        }
        close();
    }

    /**
     * Terminate this connection context
     */
    @Override
    public void terminate() {
        super.terminate();
        // Try to interrupt the handler thread, if there is one.
        if (submittedJob != null) {
            submittedJob.cancel(true);
        }
        this.submittedJob = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void canBeReused() {
        // This connection has been fully terminated, so it can now be reused
        this.contextReuseManager.returnHttp1ConnectionContext(this);
    }

// =================================================================================================================
    // Methods called by Http1RequestResponseContext

    /**
     * Handle the end of response being sent
     */
    private void responseCompletelySent() {
        System.out.println("Http1ConnectionContext.responseSent isKeepAlive="+isKeepAlive);
        if (isKeepAlive) {
            state = State.WAITING_FOR_RESPONSE_TO_BE_SENT;
//            state = State.BEGIN;
//            tempHeaderKey = null;
//            requestResponseContext.reset();
        } else {
            close();
        }
    }

    // =================================================================================================================
    // Parsing Util Methods

    private boolean searchForSpace(final int maxLengthToSearch) {
        while(inputBuffer.getNumMarkedBytes() <= maxLengthToSearch && inputBuffer.available(1)) {
            final char c = (char)inputBuffer.peekByte();
            if (c == SPACE) {
                return true;
            }
            inputBuffer.skip(1);
        }
        return false;
    }

    private boolean searchForEndOfLine(final int maxLengthToSearch) {
        while(inputBuffer.getNumMarkedBytes() <= maxLengthToSearch && inputBuffer.available(2)) {
            final char c1 = (char)inputBuffer.peekByte();
            final char c2 = (char)inputBuffer.peekByte(1);
            // look ahead for end of line
            if (c1 == CR) {
                if (c2 == LF) {
                    // all good we now have a complete start line
                    return true;
                } else {
                    requestResponseContext.respond(StatusCode.BAD_REQUEST_400);
                    return false;
                }
            }
            // we can skip 2 if c2 is not start of \r\n otherwise skip one
            if (c2 != CR) {
                inputBuffer.skip(2);
            } else {
                inputBuffer.skip(1);
            }
        }
        return false;
    }

    /**
     * Search the input stream for a header separator which matches "\s+:\s+" returning the length in chars or -1 if
     * not found before reading {@code maxLengthToSearch} chars
     *
     * @param maxLengthToSearch maximum number of char's to read ahead searching for separator
     * @return true of field separator was found
     */
    private boolean searchForHeaderSeparator(final int maxLengthToSearch) {
        while(inputBuffer.getNumMarkedBytes() <= maxLengthToSearch && inputBuffer.available(1)) {
            final char c = (char)inputBuffer.peekByte();
            if (c == COLON) {
                return true;
            }
            inputBuffer.skip(1);
        }
        return false;
    }

}

