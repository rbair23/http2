package com.hedera.hashgraph.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
     * Get the request {@link URI}.
     *
     * @return the request {@code URI}
     */
//    URI getRequestURI();

    /**
     * Get the request method.
     *
     * @return the request method
     */
    String getMethod();

    /**
     * Get the request path.
     *
     * @return the request path
     */
    String getPath();

    /**
     * Returns the protocol string from the request in the form
     * <i>protocol/majorVersion.minorVersion</i>. For example,
     * "{@code HTTP/1.1}".
     *
     * @return the protocol string from the request
     */
    String getProtocol();

    /**
     * Returns a stream from which the request body can be read.
     * Multiple calls to this method will return the same stream.
     * It is recommended that applications should consume (read) all the data
     * from this stream before closing it. If a stream is closed before all data
     * has been read, then the {@link InputStream#close()} call will read
     * and discard remaining data (up to an implementation specific number of
     * bytes).
     *
     * @return the stream from which the request body can be read
     */
    InputStream getRequestBody();

    /**
     * Responds to the request with the given response headers and code. There is no response body
     * for this response. This call implicitly calls {@link #close()}.
     *
     * @implNote This implementation allows the caller to instruct the
     * server to force a connection close after the exchange terminates, by
     * supplying a {@code Connection: close} header to the response headers.
     *
     * @param responseHeaders The response headers. If null, then empty headers are returned.
     * @param responseCode The response code.
     * @throws IOException If an I/O error occurs, or the {@link WebRequest} has already been closed.
     */
    void respond(WebHeaders responseHeaders, int responseCode) throws IOException;

    /**
     * Begins a response with the given headers and response code. The {@link OutputStream}
     * returned from this method is used for writing the response body data. It is common
     * to specify the response length in the response headers. The application terminates
     * the response body by closing the {@link OutputStream}.
     *
     * @implNote This implementation allows the caller to instruct the
     * server to force a connection close after the exchange terminates, by
     * supplying a {@code Connection: close} header to the response headers.
     *
     * <p> Closing this stream implicitly closes the {@link InputStream}
     * returned from {@link #getRequestBody()} (if it is not already closed).
     *
     * <p> If the {@code responseHeader}s specified a fixed response body length, then
     * the exact number of bytes specified in that call must be written to this stream.
     * If too many bytes are written, then the write method of {@link OutputStream} will
     * throw an {@code IOException}. If too few bytes are written then the stream
     * {@link OutputStream#close()} will throw an {@code IOException}.
     * In both cases, the exchange is aborted.
     *
     * @param responseHeaders The response headers. If null, then empty headers are returned.
     * @param responseCode The response code.
     * @return An {@link OutputStream} to which the application writes the response body.
     * @throws IOException If an I/O error occurs, or the {@link WebRequest} has already been closed.
     */
    OutputStream startResponse(WebHeaders responseHeaders, int responseCode) throws IOException;

    /**
     * Ends this exchange by doing the following in sequence:
     * <ol>
     *      <li> close the request {@link InputStream}, if not already closed.
     *      <li> close the response {@link OutputStream}, if not already closed.
     * </ol>
     */
    void close();
}
