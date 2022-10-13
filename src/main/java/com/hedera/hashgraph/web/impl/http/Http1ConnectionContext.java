package com.hedera.hashgraph.web.impl.http;

import com.hedera.hashgraph.web.ResponseAlreadySentException;
import com.hedera.hashgraph.web.StatusCode;
import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.HttpVersion;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;
import com.hedera.hashgraph.web.impl.session.RequestContext;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class Http1ConnectionContext extends RequestContext {
    private static final int MAX_METHOD_LENGTH = 1024;
    private static final int MAX_URI_LENGTH = 2024;
    private static final int MAX_VERSION_LENGTH = 8;
    private static final int MAX_HEADER_KEY_LENGTH = 1024;
    private static final int MAX_HEADER_VALUE_LENGTH = 1024;

    private static final char CR = '\r';
    private static final char LF = '\n';
    private static final char SPACE = ' ';
    private static final char COLON = ':';
    /** The last bit of HTTP2 preface after "PRI * HTTP/2.0" */
    private static final byte[] HTTP2_PREFACE_END = "\r\nSM\r\n\r\n".getBytes();

    private enum State {BEGIN,METHOD,URI,VERSION,HTTP2_PREFACE,HEADER_KEY,HEADER_VALUE,COLLECTING_BODY};

    private State state;

    private String tempHeaderKey;

    /**
     * Create a new instance.
     *
     * @param contextReuseManager
     * @param dispatcher
     */
    public Http1ConnectionContext(ContextReuseManager contextReuseManager, Dispatcher dispatcher) {
        super(contextReuseManager, dispatcher);
    }


    protected void reset() {
        super.reset();
        state = State.BEGIN;
        tempHeaderKey = null;
    }

    @Override
    public void handle(Consumer<HttpVersion> onConnectionUpgrade) {
        //         generic-message = start-line
        //                          *(message-header CRLF)
        //                          CRLF
        //                          [ message-body ]
        //        start-line      = Request-Line | Status-Line
        // loop while there is still data to process, we can go through multiple states
        try {
            while (in.available(1)) {
                switch (state) {
                    case BEGIN:
                        in.mark();
                        state = State.METHOD;
                        break;
                    case METHOD:
                        if (searchForSpace(MAX_METHOD_LENGTH)) {
                            // we found a space, so read between mark and current position as string
                            final int bytesRead = in.resetToMark();
                            method = in.readString(bytesRead, StandardCharsets.US_ASCII);
                            // skip over the space
                            in.skip(1);
                            // next state
                            in.mark();
                            state = State.URI;
                        }
                        break;
                    case URI:
                        if (searchForSpace(MAX_URI_LENGTH)) {
                            // we found a space, so read between mark and current position as string
                            final int bytesRead = in.resetToMark();
                            path = in.readString(bytesRead, StandardCharsets.US_ASCII);
                            // skip over the space
                            in.skip(1);
                            // next state
                            in.mark();
                            state = State.VERSION;
                        }
                        break;
                    case VERSION:
                        if (searchForEndOfLine(MAX_VERSION_LENGTH)) {
                            try {
                                // we found a space, so read between mark and current position as string
                                final int bytesRead = in.resetToMark();
                                version = in.readVersion();
                                // skip over the new line
                                in.skip(2);
                                // handle versions
                                switch (version) {
                                    case HTTP_1, HTTP_1_1 -> {
                                        // next state
                                        in.mark();
                                        state = State.HEADER_KEY;
                                    }
                                    case HTTP_2 -> {
                                        // next state
                                        in.mark();
                                        state = State.HTTP2_PREFACE;
                                    }
                                }
                            } catch (Exception e) {
                                respond(StatusCode.HTTP_VERSION_NOT_SUPPORTED);
                                return;
                            }
                        }
                        break;
                    case HTTP2_PREFACE:
                        if (in.available(HTTP2_PREFACE_END.length)) {
                            for (final byte b : HTTP2_PREFACE_END) {
                                if (in.readByte() != b) {
                                    respond(StatusCode.BAD_REQUEST);
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
                        if (in.available(2)) {
                            // look ahead for end of line
                            if ((char) in.peekByte() == CR) {
                                if ((char) in.peekByte(1) == LF) {
                                    // we have handled the next to chars so move ahead
                                    in.skip(2);
                                    // we are now ready for body
                                    in.mark();
                                    state = State.COLLECTING_BODY;
                                    System.out.println("requestData.getHeaders() = " + requestHeaders);
                                } else {
                                    respond(StatusCode.BAD_REQUEST);
                                }
                            }
                        }
                        // now handle normal header key
                        if (searchForHeaderSeparator(MAX_HEADER_KEY_LENGTH)) {
                            // we found a separator, so read between mark and current position as string
                            final int bytesRead = in.resetToMark();
                            tempHeaderKey = in.readString(bytesRead, StandardCharsets.US_ASCII);
                            System.out.println("headerKey = " + tempHeaderKey);
                            // skip over separator
                            in.skip(1);
                            // change to header value state
                            in.mark();
                            state = State.HEADER_VALUE;
                        }
                        break;
                    case HEADER_VALUE:
                        if (searchForEndOfLine(MAX_HEADER_VALUE_LENGTH)) {
                            // we found a end of line, so read between mark and current position as string
                            final int bytesRead = in.resetToMark();
                            final String headerValue = in.readString(bytesRead, StandardCharsets.US_ASCII)
                                    .trim(); // TODO is trim efficient enough and is it too tolerant of white space chars
                            System.out.println("        headerValue = " + headerValue);
                            requestHeaders.put(tempHeaderKey, headerValue);
                            // skip over separator
                            in.skip(2);
                            // change to header value state
                            in.mark();
                            state = State.HEADER_VALUE;
                        }
                        break;
                    case COLLECTING_BODY:
                        // read unit we have read content length
                        if (requestHeaders.getContentLength() == -1) {
                            // go back to beginning to handle another request
                            reset();
                        } else {
                            // TODO handle "Transfer-Encoding" eg "gzip, chunked"
                            while (in.available(1) && getRequestBodyLength() < requestHeaders.getContentLength()) {
                                requestBody[getRequestBodyLength()] = in.readByte();
                            }
                            // check if we are done
                            if (getRequestBodyLength() == requestHeaders.getContentLength()) {
                                dispatcher.dispatch(this);
                                // reset for next HTTP 1.1 request
                                reset();
                            }
                        }
                        break;
                }
            }
        } catch (ResponseAlreadySentException e) {
            e.printStackTrace();
        }
    }


    // =================================================================================================================
    // Parsing Util Methods

    private boolean searchForSpace(int maxLengthToSearch) {
        while(in.getNumMarkedBytes() <= maxLengthToSearch && in.available(1)) {
            final char c = (char)in.peekByte();
            if (c == SPACE) {
                return true;
            }
            in.skip(1);
        }
        return false;
    }

    private boolean searchForEndOfLine(int maxLengthToSearch) throws ResponseAlreadySentException {
        while(in.getNumMarkedBytes() <= maxLengthToSearch && in.available(2)) {
            final char c1 = (char)in.peekByte();
            final char c2 = (char)in.peekByte(1);
            // look ahead for end of line
            if (c1 == CR) {
                if (c2 == LF) {
                    // all good we now have a complete start line
                    return true;
                } else {
                    respond(StatusCode.BAD_REQUEST);
                    return false;
                }
            }
            // we have handled the next to chars so move ahead
            in.skip(2);
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
    private boolean searchForHeaderSeparator(int maxLengthToSearch) {
        while(in.getNumMarkedBytes() <= maxLengthToSearch && in.available(1)) {
            final char c = (char)in.peekByte();
            if (c == COLON) {
                return true;
            }
            in.skip(1);
        }
        return false;
    }


    // =================================================================================================================
    // WebRequest Methods


    @Override
    public OutputStream startResponse(StatusCode statusCode, WebHeaders responseHeaders) throws ResponseAlreadySentException {
        responseCode = statusCode;
        responseHeaders = requestHeaders;
        try {
            sendHeader();
        } catch (IOException e) {
            e.printStackTrace(); // TODO not sure here
        }
        return new HttpOutputStream(responseHeaders, outputBuffer, channel); // TODO needs to be much cleaver
    }

    @Override
    public void respond(StatusCode statusCode, WebHeaders responseHeaders) throws ResponseAlreadySentException {
        responseCode = statusCode;
        responseHeaders = requestHeaders;
        try {
            sendHeader();
        } catch (IOException e) {
            e.printStackTrace(); // TODO not sure here
        }
    }

    @Override
    public void respond(StatusCode statusCode) throws ResponseAlreadySentException {
        responseCode = statusCode;
        try {
            sendHeader();
        } catch (IOException e) {
            e.printStackTrace(); // TODO not sure here
        }
    }

    private void sendHeader() throws IOException {
        // CHECK THAT WE HAVE ALL NEEDED HEADERS

        // SEND DATA
        outputBuffer.reset();
        // send response line
        outputBuffer.write(version.versionString());
        outputBuffer.write(SPACE);
        outputBuffer.write(Integer.toString(responseCode.code));
        outputBuffer.write(SPACE);
        outputBuffer.write(responseCode.name());
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
        outputBuffer.sendContentsToChannel(channel);
    }

    // TODO needs to handle chunking and gzip etc
    private class HttpOutputStream extends OutputStream {
        private final OutputBuffer outputBuffer;
        private final SocketChannel channel;

        public HttpOutputStream(WebHeaders responseHeaders, OutputBuffer outputBuffer, SocketChannel channel) {
            this.outputBuffer = outputBuffer;
            this.channel = channel;
            outputBuffer.reset();
        }

        @Override
        public void write(int b) throws IOException {
            outputBuffer.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            outputBuffer.write(b, off,len);
        }

        @Override
        public void close() throws IOException {
            super.close();
            outputBuffer.sendContentsToChannel(channel);
        }
    }
}
