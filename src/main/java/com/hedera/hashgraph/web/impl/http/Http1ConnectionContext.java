package com.hedera.hashgraph.web.impl.http;

import com.hedera.hashgraph.web.ResponseAlreadySentException;
import com.hedera.hashgraph.web.StatusCode;
import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.HttpVersion;
import com.hedera.hashgraph.web.impl.session.ConnectionContext;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static com.hedera.hashgraph.web.impl.http.Http1Constants.*;


/**
 * The HTTP 1.1 spec is vast, there are a number of things not supported here. Bellow is a list of them:
 * <ul>
 *     <li><a href="https://httpwg.org/specs/rfc9110.html#trailer.fields">Trailer Fields</a></li>
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
        START_OF_BODY,
        WAITING_FOR_RESPONSE_TO_BE_SENT
    };

    /** Parsing state machine current state */
    private State state;

    /** Temporary string used during parsing to hold a header key until we finish parsing its value */
    private String tempHeaderKey;

    private final Http1RequestContext requestContext;

    private final AtomicBoolean responseSent = new AtomicBoolean();

    /**
     * Create a new instance.
     *
     * @param contextReuseManager
     * @param dispatcher
     */
    public Http1ConnectionContext(final ContextReuseManager contextReuseManager, final Dispatcher dispatcher) {
        super(contextReuseManager, 16*1024);
        this.requestContext = new Http1RequestContext(dispatcher, outputBuffer, () -> responseSent.set(true));
    }

    @Override
    public void reset(SocketChannel channel, Runnable onCloseCallback) {
        super.reset(channel, onCloseCallback);
        state = State.BEGIN;
        tempHeaderKey = null;
        responseSent.set(false);
        requestContext.setChannel(channel);
    }

    @Override
    public void close() {
        super.close();
        contextReuseManager.returnHttp1ConnectionContext(this);
    }

    @Override
    public boolean doHandle(Consumer<HttpVersion> onConnectionUpgrade) {
        //         generic-message = start-line
        //                          *(message-header CRLF)
        //                          CRLF
        //                          [ message-body ]
        //        start-line      = Request-Line | Status-Line
        // loop while there is still data to process, we can go through multiple states
        try {
            while (inputBuffer.available(1)) {
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
                                return respondWithError(StatusCode.BAD_REQUEST_400, new WebHeaders(),
                                        "Bad URI: "+e.getMessage());
                            }
                        }
                        break;
                    case VERSION:
                        if (searchForEndOfLine(MAX_VERSION_LENGTH)) {
                            try {
                                // we found a space, so read between mark and current position as string
                                final int bytesRead = inputBuffer.resetToMark();
                                requestContext.setVersion(inputBuffer.readVersion());
                                // check for unknown version
                                if (requestContext.getVersion() == null) {
                                    return respondWithError(StatusCode.HTTP_VERSION_NOT_SUPPORTED_505);
                                }
                                // skip over the new line
                                inputBuffer.skip(2);
                                // handle versions
                                switch (requestContext.getVersion()) {
                                    case HTTP_1 -> {
                                        return respondWithError(StatusCode.UPGRADE_REQUIRED_426,
                                                new WebHeaders()
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
                            return false;
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
                                    state = State.START_OF_BODY;
                                    System.out.println("requestContext.getRequestHeaders() = " + requestContext.getRequestHeaders());
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
                            System.out.println("headerKey = " + tempHeaderKey);
                            // skip over separator
                            inputBuffer.skip(1);
                            // change to header value state
                            inputBuffer.mark();
                            state = State.HEADER_VALUE;
                        }
                        break;
                    case HEADER_VALUE:
                        if (searchForEndOfLine(MAX_HEADER_VALUE_LENGTH)) {
                            // we found a end of line, so read between mark and current position as string
                            final int bytesRead = inputBuffer.resetToMark();
                            final String headerValue = inputBuffer.readString(bytesRead, StandardCharsets.US_ASCII)
                                    .trim(); // TODO is trim efficient enough and is it too tolerant of white space chars
                            System.out.println("        headerValue = " + headerValue);
                            requestContext.getRequestHeaders().put(tempHeaderKey, headerValue);
                            // skip over separator
                            inputBuffer.skip(2);
                            // change to header value state
                            inputBuffer.mark();
                            state = State.HEADER_VALUE;
                        }
                        break;
                    case START_OF_BODY:
                        // read unit we have read content length
                        // TODO handle "Transfer-Encoding" eg "gzip, chunked"
                        final int bodySize = requestContext.getRequestHeaders().getContentLength();
                        if (bodySize <= 0) {
                            throw new RuntimeException("Bad body size ["+bodySize+"]");
                        }
                        if (inputBuffer.available(bodySize)) {
                            requestContext.setRequestBody(new BodyInputStream(inputBuffer,bodySize));
                            requestContext.dispatch();
                            state = State.WAITING_FOR_RESPONSE_TO_BE_SENT;
                            return true;
                        }
                        break;
                    case WAITING_FOR_RESPONSE_TO_BE_SENT:
                        if (responseSent.get()) {
                            // reset for next HTTP 1.1 request
                            reset(channel,null);
                        } else {
                            return true; // waiting for response to be sent
                        }
                }
            }
        } catch (ResponseAlreadySentException e) {
            e.printStackTrace();
        }
        return false;
    }

    private boolean respondWithError(StatusCode statusCode) {
        state = State.WAITING_FOR_RESPONSE_TO_BE_SENT;
        requestContext.respond(statusCode);
        return true;
    }

    private boolean respondWithError(StatusCode statusCode, WebHeaders webHeaders, String bodyMessage) {
        state = State.WAITING_FOR_RESPONSE_TO_BE_SENT;
        requestContext.respond(statusCode,webHeaders,bodyMessage);
        return true;
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
                    requestContext.respond(StatusCode.BAD_REQUEST_400);
                    return false;
                }
            }
            // we have handled the next to chars so move ahead
            inputBuffer.skip(2);
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

