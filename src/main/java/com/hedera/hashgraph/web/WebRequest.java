package com.hedera.hashgraph.web;

import com.sun.net.httpserver.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;

public interface WebRequest extends AutoCloseable {
    // TODO Redo the comments in here....
    /**
     * Returns an immutable {@link Map} containing the HTTP headers that were
     * included with this request. The keys in this {@code Map} will be the header
     * names, while the values will be a {@link java.util.List} of
     * {@linkplain java.lang.String Strings} containing each value that was
     * included (either for a header that was listed several times, or one that
     * accepts a comma-delimited list of values on a single line). In either of
     * these cases, the values for the header name will be presented in the
     * order that they were included in the request.
     *
     * <p> The keys in {@code Map} are case-insensitive.
     *
     * @return a read-only {@code Map} which can be used to access request headers
     */
    WebHeaders getRequestHeaders();

    /**
     * Returns a mutable {@link Map} into which the HTTP response headers can be
     * stored and which will be transmitted as part of this response. The keys in
     * the {@code Map} will be the header names, while the values must be a
     * {@link java.util.List} of {@linkplain java.lang.String Strings} containing
     * each value that should be included multiple times (in the order that they
     * should be included).
     *
     * <p> The keys in {@code Map} are case-insensitive.
     *
     * @return a writable {@code Map} which can be used to set response headers.
     */
    WebHeaders getResponseHeaders();

    /**
     * Get the request {@link URI}.
     *
     * @return the request {@code URI}
     */
//    URI getRequestURI();

    /**
     * Get the request path.
     *
     * @return the request path
     */
    String getPath();

    /**
     * Get the request method.
     *
     * @return the request method
     */
    String getRequestMethod();

    /**
     * Get the {@link HttpContext} for this exchange.
     *
     * @return the {@code HttpContext}
     */
//    public abstract HttpContext getHttpContext();

    /**
     * Ends this exchange by doing the following in sequence:
     * <ol>
     *      <li> close the request {@link InputStream}, if not already closed.
     *      <li> close the response {@link OutputStream}, if not already closed.
     * </ol>
     */
    void close();

    /**
     * Returns a stream from which the request body can be read.
     * Multiple calls to this method will return the same stream.
     * It is recommended that applications should consume (read) all of the data
     * from this stream before closing it. If a stream is closed before all data
     * has been read, then the {@link InputStream#close()} call will read
     * and discard remaining data (up to an implementation specific number of
     * bytes).
     *
     * @return the stream from which the request body can be read
     */
    InputStream getRequestBody();

    /**
     * Returns a stream to which the response body must be
     * written. {@link #sendResponseHeaders(int,long)}) must be called prior to
     * calling this method. Multiple calls to this method (for the same exchange)
     * will return the same stream. In order to correctly terminate each exchange,
     * the output stream must be closed, even if no response body is being sent.
     *
     * <p> Closing this stream implicitly closes the {@link InputStream}
     * returned from {@link #getRequestBody()} (if it is not already closed).
     *
     * <p> If the call to {@link #sendResponseHeaders(int, long)} specified a
     * fixed response body length, then the exact number of bytes specified in
     * that call must be written to this stream. If too many bytes are written,
     * then the write method of {@link OutputStream} will throw an {@code IOException}.
     * If too few bytes are written then the stream
     * {@link OutputStream#close()} will throw an {@code IOException}.
     * In both cases, the exchange is aborted and the underlying TCP connection
     * closed.
     *
     * @return the stream to which the response body is written
     */
    OutputStream getResponseBody();


    /**
     * Starts sending the response back to the client using the current set of
     * response headers and the numeric response code as specified in this
     * method. The response body length is also specified as follows. If the
     * response length parameter is greater than {@code zero}, this specifies an
     * exact number of bytes to send and the application must send that exact
     * amount of data. If the response length parameter is {@code zero}, then
     * chunked transfer encoding is used and an arbitrary amount of data may be
     * sent. The application terminates the response body by closing the
     * {@link OutputStream}.
     * If response length has the value {@code -1} then no response body is
     * being sent.
     *
     * <p> If the content-length response header has not already been set then
     * this is set to the appropriate value depending on the response length
     * parameter.
     *
     * <p> This method must be called prior to calling {@link #getResponseBody()}.
     *
     * @implNote This implementation allows the caller to instruct the
     * server to force a connection close after the exchange terminates, by
     * supplying a {@code Connection: close} header to the {@linkplain
     * #getResponseHeaders() response headers} before {@code sendResponseHeaders}
     * is called.
     *
     * @param rCode          the response code to send
     * @param responseLength if {@literal > 0}, specifies a fixed response body
     *                       length and that exact number of bytes must be written
     *                       to the stream acquired from {@link #getResponseCode()}
     *                       If {@literal == 0}, then chunked encoding is used,
     *                       and an arbitrary number of bytes may be written.
     *                       If {@literal <= -1}, then no response body length is
     *                       specified and no response body may be written.
     * @throws IOException   if the response headers have already been sent or an I/O error occurs
     * @see   HttpExchange#getResponseBody()
     */
    void sendResponseHeaders(int rCode, long responseLength) throws IOException;

    /**
     * Returns the address of the remote entity invoking this request.
     *
     * @return the {@link InetSocketAddress} of the caller
     */
//    InetSocketAddress getRemoteAddress();

    /**
     * Returns the response code, if it has already been set.
     *
     * @return the response code, if available. {@code -1} if not available yet.
     */
    int getResponseCode();

    /**
     * Returns the local address on which the request was received.
     *
     * @return the {@link InetSocketAddress} of the local interface
     */
//    InetSocketAddress getLocalAddress();

    /**
     * Returns the protocol string from the request in the form
     * <i>protocol/majorVersion.minorVersion</i>. For example,
     * "{@code HTTP/1.1}".
     *
     * @return the protocol string from the request
     */
    String getProtocol();
}
