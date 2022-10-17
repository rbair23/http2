package com.hedera.hashgraph.web;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Represents the response to a request.
 */
public interface WebResponse {
    String CONTENT_TYPE_PLAIN_TEXT = "/text/plain";
    String CONTENT_TYPE_HTML = "text/html; charset=UTF-8";
    String CONTENT_TYPE_JSON = "application/json";

    String CONTENT_ENCODING_GZIP = "gzip";
    String CONTENT_ENCODING_COMPRESS = "compress";
    String CONTENT_ENCODING_DEFLATE = "deflate";

    /**
     * Returns a collection of all response headers. The keys are case-insensitive.
     *
     * @return a mutable {@link WebResponse} which can be used to set response headers
     */
    WebHeaders getHeaders();

    /**
     * Sets the content type of the response
     *
     * @param value The content type to use
     * @return A reference to this response
     */
    default WebResponse contentType(final String value) {
        getHeaders().setContentType(value);
        return this;
    }

    /**
     * Sets an arbitrary header in the response
     *
     * @param key The header key
     * @param value The header value
     * @return A reference to this response
     */
    default WebResponse header(String key, String value) {
        getHeaders().put(key, value);
        return this;
    }

    /**
     * Set the status code of the response. The default code is 200 OK.
     *
     * @param code The code to use. Cannot be null.
     * @return A reference to this response
     */
    WebResponse statusCode(StatusCode code);

    /**
     * Sets the response body as a string.
     *
     * @param bodyAsString The response body. If the value is null, then no response is sent.
     *                     If it is not null, the "content-length" header will be set based
     *                     on the byte length of this string.
     * @return A reference to this response
     * @throws ResponseAlreadySentException any of the "body" methods have already been called.
     */
    WebResponse body(String bodyAsString) throws ResponseAlreadySentException;

    /**
     * Sets the response body as a byte[].
     *
     * @param bodyAsBytes The response body. If the value is null, then no response is sent.
     *                    If it is not null, the "content-length" header will be set based
     *                    on the length of this array.
     * @return A reference to this response
     * @throws ResponseAlreadySentException any of the "body" methods have already been called.
     */
    WebResponse body(byte[] bodyAsBytes) throws ResponseAlreadySentException;

    /**
     * Gets an OutputStream into which bytes can be written.
     *
     * @return A reference to this response
     * @throws ResponseAlreadySentException any of the "body" methods have already been called.
     * @throws IOException If the underlying connection is closed
     */
    OutputStream body() throws ResponseAlreadySentException, IOException;
}
