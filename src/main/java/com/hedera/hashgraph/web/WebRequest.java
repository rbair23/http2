package com.hedera.hashgraph.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Contains all data related to the web request, and provides the means to create a
 * response to send data back to the client.
 */
public interface WebRequest extends AutoCloseable {
    /**
     * Returns an immutable {@link WebHeaders} containing the HTTP headers that were
     * included with this request. The keys in this object will be the header
     * names, while the values will be a {@link java.util.List} of
     * {@linkplain java.lang.String Strings} containing each value that was
     * included (either for a header that was listed several times, or one that
     * accepts a comma-delimited list of values on a single line). In either of
     * these cases, the values for the header name will be presented in the
     * order that they were included in the request.
     *
     * <p> The keys in {@link WebHeaders} are case-insensitive.
     *
     * @return a read-only {@code WebHeaders} which can be used to access request headers
     */
    WebHeaders getRequestHeaders();

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
     * Returns the protocol string from the request in the form <i>protocol/majorVersion.minorVersion</i>.
     * For example, "{@code HTTP/1.1}".
     *
     * @return the protocol string from the request
     */
    HttpVersion getVersion();

    /**
     * Returns a stream from which the request body can be read.
     * <p>
     * Multiple calls to this method will return the same stream. It is recommended that applications
     * should consume (read) all the data from this stream before closing it. If a stream is closed
     * before all data has been read, then the {@link InputStream#close()} call will read and discard
     * remaining data (up to an implementation specific number of bytes).
     *
     * @return the stream from which the request body can be read
     */
    InputStream getRequestBody() throws IOException;

    /**
     * Creates a {@link WebResponse} that can be used to send a response to the client.
     *
     * @throws ResponseAlreadySentException if any of the "response" methods has already been called.
     */
    WebResponse respond() throws ResponseAlreadySentException;

    /**
     * Responds to the request with the given status code. There is no response body
     * for this response. This call concludes the request and returns the status code to the client.
     *
     * @param statusCode The response code.
     * @throws ResponseAlreadySentException if any of the "response" methods has already been called.
     */
    default void respond(final StatusCode statusCode) throws ResponseAlreadySentException {
        Objects.requireNonNull(statusCode);
        respond().statusCode(statusCode);
    }
}
