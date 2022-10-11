package com.hedera.hashgraph.web.impl.http;

import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.WebRequest;
import com.hedera.hashgraph.web.WebRoutes;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.HttpInputStream;
import com.hedera.hashgraph.web.impl.ProtocolHandler;
import com.hedera.hashgraph.web.impl.WebRequestImpl;
import com.hedera.hashgraph.web.impl.http2.Http2ProtocolHandler;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.BiConsumer;

public class HttpProtocolHandler implements ProtocolHandler {
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

    public static final int BEGIN = 0;
    public static final int METHOD = 1;
    public static final int URI = 2;
    public static final int VERSION = 3;
    public static final int HTTP2_PREFACE = 4;
    public static final int HEADER_KEY = 5;
    public static final int HEADER_VALUE = 6;
    public static final int COLLECTING_BODY = 7;

    private final WebRoutes routes;
    private final Http2ProtocolHandler http2ProtocolHandler;

    public HttpProtocolHandler(WebRoutes routes, Http2ProtocolHandler http2ProtocolHandler) {
        this.routes = Objects.requireNonNull(routes);
        this.http2ProtocolHandler = Objects.requireNonNull(http2ProtocolHandler);
    }

    @Override
    public void handle(Dispatcher.ChannelData channelData, BiConsumer<Dispatcher.ChannelData, WebRequestImpl> doDispatch) {
        final HttpInputStream in = channelData.getIn();
        final var requestData = channelData.getSingleStreamData();
        //         generic-message = start-line
        //                          *(message-header CRLF)
        //                          CRLF
        //                          [ message-body ]
        //        start-line      = Request-Line | Status-Line
        // loop while there is still data to process, we can go through multiple states
        while(in.available(1)) {
            switch (requestData.getState()) {
                case BEGIN:
                    in.mark();
                    requestData.setState(METHOD);
                    break;
                case METHOD:
                    if (searchForSpace(in, MAX_METHOD_LENGTH)) {
                        // we found a space, so read between mark and current position as string
                        final int bytesRead = in.resetToMark();
                        requestData.setMethod(in.readString(bytesRead, StandardCharsets.US_ASCII));
                        // skip over the space
                        in.skip(1);
                        // next state
                        in.mark();
                        requestData.setState(URI);
                    }
                    break;
                case URI:
                    if (searchForSpace(in, MAX_URI_LENGTH)) {
                        // we found a space, so read between mark and current position as string
                        final int bytesRead = in.resetToMark();
                        requestData.setPath(in.readString(bytesRead, StandardCharsets.US_ASCII));
                        // skip over the space
                        in.skip(1);
                        // next state
                        in.mark();
                        requestData.setState(VERSION);
                    }
                    break;
                case VERSION:
                    if (searchForEndOfLine(channelData, in, MAX_VERSION_LENGTH)) {
                        // we found a space, so read between mark and current position as string
                        final int bytesRead = in.resetToMark();
                        final String versionString = in.readString(bytesRead, StandardCharsets.US_ASCII);
                        requestData.setVersion(versionString);
                        // skip over the new line
                        in.skip(2);
                        // handle versions
                        switch (versionString) {
                            case "HTTP/1.0":
                            case "HTTP/1.1":
                                // next state
                                in.mark();
                                requestData.setState(HEADER_KEY);
                                requestData.setHeaders(new WebHeaders());
                                break;
                            case "HTTP/2.0":
                                // next state
                                in.mark();
                                requestData.setState(HTTP2_PREFACE);
                                break;
                            default:
                                sendErrorCodeResponse(channelData, StatusCode.HTTP_VERSION_NOT_SUPPORTED);
                                return;
                        }
                    }
                    break;
                case HTTP2_PREFACE:
                    if (in.available(HTTP2_PREFACE_END.length)) {
                        for (final byte b : HTTP2_PREFACE_END) {
                            if (in.readByte() != b) {
                                sendErrorCodeResponse(channelData, StatusCode.BAD_REQUEST);
                                return;
                            }
                        }
                        // full preface read, now hand over to http 2 handler
                        requestData.setState(0);
                        channelData.setProtocolHandler(http2ProtocolHandler);
                        return;
                    }
                    break;
                case HEADER_KEY:
                    // check for end of headers with a double new line
                    if(in.available(2)) {
                        // look ahead for end of line
                        if ((char)in.peekByte() == CR) {
                            if ((char)in.peekByte(1) == LF) {
                                // we have handled the next to chars so move ahead
                                in.skip(2);
                                // we are now ready for body
                                in.mark();
                                requestData.setState(COLLECTING_BODY);
                                System.out.println("requestData.getHeaders() = " + requestData.getHeaders());
                            } else {
                                sendErrorCodeResponse(channelData, StatusCode.BAD_REQUEST);
                            }
                        }
                    }
                    // now handle normal header key
                    if (searchForHeaderSeparator(channelData, in, MAX_HEADER_KEY_LENGTH)) {
                        // we found a separator, so read between mark and current position as string
                        final int bytesRead = in.resetToMark();
                        final String headerKey = in.readString(bytesRead, StandardCharsets.US_ASCII);
                        System.out.println("headerKey = " + headerKey);
                        requestData.setTempString(headerKey);
                        // skip over separator
                        in.skip(1);
                        // change to header value state
                        in.mark();
                        requestData.setState(HEADER_VALUE);
                    }
                    break;
                case HEADER_VALUE:
                    if (searchForEndOfLine(channelData, in, MAX_HEADER_VALUE_LENGTH)) {
                        // we found a end of line, so read between mark and current position as string
                        final int bytesRead = in.resetToMark();
                        final String headerValue = in.readString(bytesRead, StandardCharsets.US_ASCII)
                                .trim(); // TODO is trim efficient enough and is it too tolerant of white space chars
                        System.out.println("        headerValue = " + headerValue);
                        requestData.getHeaders().put(requestData.getTempString(), headerValue);
                        // skip over separator
                        in.skip(2);
                        // change to header value state
                        in.mark();
                        requestData.setState(HEADER_VALUE);
                    }
                    break;
                case COLLECTING_BODY:
                    // TODO handle "Transfer-Encoding" eg "gzip, chunked"
                    // we have finished with body so dispatch
                    doDispatch.accept(channelData,requestData);
                    // set state back to beginning in case we have another HTTP 1.1 request
                    requestData.close();
                    return;
                default:
                    throw new IllegalStateException("Unexpected value: " + requestData.getState());
            }
        }
    }

    @Override
    public void onServerError(Dispatcher.ChannelData channelData, WebRequestImpl request, RuntimeException ex) {

    }

    @Override
    public void onEndOfRequest(Dispatcher.ChannelData channelData, WebRequestImpl request) {

    }

    @Override
    public void on404(Dispatcher.ChannelData channelData, WebRequestImpl request) {

    }

    private boolean searchForSpace(HttpInputStream in, int maxLengthToSearch) {
        while(in.getNumMarkedBytes() <= maxLengthToSearch && in.available(1)) {
            final char c = (char)in.peekByte();
            if (c == SPACE) {
                return true;
            }
            in.skip(1);
        }
        return false;
    }

    private boolean searchForEndOfLine(Dispatcher.ChannelData channelData, HttpInputStream in, int maxLengthToSearch) {
        while(in.getNumMarkedBytes() <= maxLengthToSearch && in.available(2)) {
            final char c1 = (char)in.peekByte();
            final char c2 = (char)in.peekByte(1);
            // look ahead for end of line
            if (c1 == CR) {
                if (c2 == LF) {
                    // all good we now have a complete start line
                    return true;
                } else {
                    sendErrorCodeResponse(channelData, StatusCode.BAD_REQUEST);
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
     * @param channelData channel data reference if we need to send error response
     * @param in the input stream to search in
     * @param maxLengthToSearch maximum number of char's to read ahead searching for separator
     * @return true of field separator was found
     */
    private boolean searchForHeaderSeparator(Dispatcher.ChannelData channelData, HttpInputStream in, int maxLengthToSearch) {
        while(in.getNumMarkedBytes() <= maxLengthToSearch && in.available(1)) {
            final char c = (char)in.peekByte();
            if (c == COLON) {
                return true;
            }
            in.skip(1);
        }
        return false;
    }

    private void sendErrorCodeResponse(Dispatcher.ChannelData channelData, StatusCode statusCode) {

    }

}
