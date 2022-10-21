package com.hedera.hashgraph.web.impl.http;

import com.hedera.hashgraph.web.HttpVersion;
import com.hedera.hashgraph.web.ResponseAlreadySentException;
import com.hedera.hashgraph.web.StatusCode;
import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.WebHeadersImpl;
import com.hedera.hashgraph.web.impl.session.ConnectionContext;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;
import com.hedera.hashgraph.web.impl.session.HandleResponse;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.ByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;
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
        WAITING_FOR_END_OF_REQUEST_BODY
    };

    /** Parsing state machine current state */
    private State state;

    /** Temporary string used during parsing to hold a header key until we finish parsing its value */
    private String tempHeaderKey;

    private final Http1RequestContext requestContext;

    private boolean isKeepAlive = false;

    /**
     * Create a new instance.
     *
     * @param contextReuseManager The {@link ContextReuseManager} that manages this instance. Must not be null.
     * @param dispatcher The dispatcher for handling incoming web requests
     */
    public Http1ConnectionContext(final ContextReuseManager contextReuseManager, final Dispatcher dispatcher) {
        super(contextReuseManager, 16*1024);
        this.requestContext = new Http1RequestContext(dispatcher, contextReuseManager::checkoutOutputBuffer, outputBuffer -> {
            sendOutput(outputBuffer);
            if (isKeepAlive) {
                state = State.BEGIN;
                tempHeaderKey = null;
                isKeepAlive = false;
            } else {
                close();
            }
        });
    }

    @Override
    public void reset(ByteChannel channel, final BiConsumer<Boolean, ConnectionContext> onCloseCallback) {
        super.reset(channel, onCloseCallback);
        state = State.BEGIN;
        tempHeaderKey = null;
        isKeepAlive = false;
        requestContext.setChannel(channel);
    }

    @Override
    public void close() {
        super.close();
        contextReuseManager.returnHttp1ConnectionContext(this);
    }

    @Override
    protected HandleResponse doHandle(Consumer<HttpVersion> onConnectionUpgrade) {
        //         generic-message = start-line
        //                          *(message-header CRLF)
        //                          CRLF
        //                          [ message-body ]
        //        start-line      = Request-Line | Status-Line
        // loop while there is still data to process, we can go through multiple states
        try {
            while (inputBuffer.available(1)) {
//                System.out.println("state = " + state);
                switch (state) {
                    case BEGIN:
                        inputBuffer.mark();
                        state = State.METHOD;
                        break;
                    case METHOD:
                        if (searchForSpace(MAX_METHOD_LENGTH)) {
                            // we found a space, so read between mark and current position as string
                            final int bytesRead = inputBuffer.resetToMark();
                            requestContext.setMethod(inputBuffer.readString(bytesRead, StandardCharsets.US_ASCII));
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
                                requestContext.setPath(uri.getPath());
                                // skip over the space
                                inputBuffer.skip(1);
                                // next state
                                inputBuffer.mark();
                                state = State.VERSION;
                            } catch (URISyntaxException e) {
                                e.printStackTrace();
                                return respondWithError(StatusCode.BAD_REQUEST_400, new WebHeadersImpl(),
                                        "Bad URI: "+e.getMessage());
                            }
                        }
                        break;
                    case VERSION:
                        if (searchForEndOfLine(MAX_VERSION_LENGTH)) {
                            try {
                                // we found a space, so read between mark and current position as string
                                final int bytesRead = inputBuffer.resetToMark();
                                final var version = inputBuffer.readVersion();

                                // check for unknown version
                                if (version == null) {
                                    return respondWithError(StatusCode.HTTP_VERSION_NOT_SUPPORTED_505);
                                }
                                // skip over the new line
                                inputBuffer.skip(2);
                                // handle versions
                                switch (version) {
                                    case HTTP_1 -> {
                                        return respondWithError(StatusCode.UPGRADE_REQUIRED_426,
                                                new WebHeadersImpl()
                                                        .put("Upgrade","HTTP/1.1, HTTP/2.0")
                                                        .put("Connection","Upgrade"),
                                                "This service requires use of the HTTP/1.1 or HTTP/2.0 protocol.");
                                    }
                                    case HTTP_1_1 -> {
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
                                return respondWithError(StatusCode.HTTP_VERSION_NOT_SUPPORTED_505);
                            }
                        }
                        break;
                    case HTTP2_PREFACE:
                        if (inputBuffer.available(HTTP2_PREFACE_END.length)) {
                            for (final byte b : HTTP2_PREFACE_END) {
                                if (inputBuffer.readByte() != b) {
                                    return respondWithError(StatusCode.BAD_REQUEST_400);
                                }
                            }
                            // full preface read, now hand over to http 2 handler
                            onConnectionUpgrade.accept(HttpVersion.HTTP_2);
                            return HandleResponse.ALL_DATA_HANDLED;
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
//                                    System.out.println("requestContext.getRequestHeaders() = " + requestContext.getRequestHeaders());
                                    // check headers
                                    isKeepAlive = requestContext.getRequestHeaders().getKeepAlive();
//                                    System.out.println("isKeepAlive = " + isKeepAlive);
                                    // check if there is a request body to read
                                    final int bodySize = requestContext.getRequestHeaders().getContentLength();
//                                    System.out.println("bodySize = " + bodySize);
                                    if (bodySize > 0) {
                                        if (inputBuffer.available(bodySize)) {
                                            requestContext.setRequestBody(new BodyInputStream(inputBuffer, bodySize));
                                        } else {
                                            state = State.WAITING_FOR_END_OF_REQUEST_BODY;
                                            return HandleResponse.ALL_DATA_HANDLED;
                                        }
                                    }
                                    // dispatch
                                    requestContext.dispatch();
                                    // we have done our job reading data, now up to dispatcher to send response and the
                                    // call callback.
                                    return HandleResponse.ALL_DATA_HANDLED;
                                } else {
                                    respondWithError(StatusCode.BAD_REQUEST_400);
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
                            requestContext.getRequestHeaders().put(tempHeaderKey, headerValue);
                            // skip over separator
                            inputBuffer.skip(2);
                            // change to header value state
                            inputBuffer.mark();
                            state = State.HEADER_KEY;
                        }
                        break;
                    case WAITING_FOR_END_OF_REQUEST_BODY:
                        final int bodySize = requestContext.getRequestHeaders().getContentLength();
                        if (inputBuffer.available(bodySize)) {
                            requestContext.setRequestBody(new BodyInputStream(inputBuffer,bodySize));
                            requestContext.dispatch();
                            // we have done our job reading data, now up to dispatcher to send response and the
                            // call callback.
                            return HandleResponse.ALL_DATA_HANDLED;
                        }
                        break;
                }
            }
        } catch (ResponseAlreadySentException e) {
            e.printStackTrace();
        }
        return HandleResponse.ALL_DATA_HANDLED;
    }

    private HandleResponse respondWithError(StatusCode statusCode) {
        requestContext.setResponseStatusCode(statusCode);
        return isKeepAlive ? HandleResponse.ALL_DATA_HANDLED : HandleResponse.CLOSE_CONNECTION;
    }

    private HandleResponse respondWithError(StatusCode statusCode, WebHeaders webHeaders, String bodyMessage) {
        requestContext.respond(statusCode,webHeaders,bodyMessage);
        return isKeepAlive ? HandleResponse.ALL_DATA_HANDLED : HandleResponse.CLOSE_CONNECTION;
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

    private boolean searchForEndOfLine(final int maxLengthToSearch) throws ResponseAlreadySentException {
        while(inputBuffer.getNumMarkedBytes() <= maxLengthToSearch && inputBuffer.available(2)) {
            final char c1 = (char)inputBuffer.peekByte();
            final char c2 = (char)inputBuffer.peekByte(1);
            // look ahead for end of line
            if (c1 == CR) {
                if (c2 == LF) {
                    // all good we now have a complete start line
                    return true;
                } else {
                    requestContext.setResponseStatusCode(StatusCode.BAD_REQUEST_400);
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

