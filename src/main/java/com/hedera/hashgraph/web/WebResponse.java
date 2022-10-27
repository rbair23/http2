package com.hedera.hashgraph.web;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Represents the response to a request. It acts like a builder, to build up headers before calling one of the
 * "respond" methods. You can only call one respond method and once a "respond" method has been called all methods throw
 * an {@link IllegalStateException}
 */
public interface WebResponse extends AutoCloseable {
    String CONTENT_TYPE_PLAIN_TEXT = "text/plain";
    String CONTENT_TYPE_HTML_TEXT = "text/html";
    String CONTENT_TYPE_HTML = "text/html; charset=UTF-8";
    String CONTENT_TYPE_JSON = "application/json";

    String CONTENT_ENCODING_GZIP = "gzip";
    String CONTENT_ENCODING_COMPRESS = "compress";
    String CONTENT_ENCODING_DEFLATE = "deflate";

    /**
     * Returns a collection of all response headers. The keys are case-insensitive.
     *
     * @return a mutable {@link WebResponse} which can be used to set response headers
     * @throws IllegalStateException if a "respond" method has been called
     */
    WebHeaders getHeaders();

    /**
     * Sets the content type of the response
     *
     * @param value The content type to use
     * @return A reference to this response
     * @throws IllegalStateException if a "respond" method has been called
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
     * @throws IllegalStateException if a "respond" method has been called
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
     * @throws IllegalStateException if a "respond" method has been called
     */
    WebResponse statusCode(StatusCode code);

    /**
     * Send the response just a status code and no body, this is used for errors. No exceptions are thrown if this fails
     * to send. We just do our best effort to send back to client
     *
     * @param code The status code to send
     */
    void respond(StatusCode code);

    /**
     * Send the response with a String body. With the string encoded as UTF-8,
     *
     * @param contentType The content type for the body
     * @param bodyAsString The response body. If the value is null, then no response is sent.
     *                     If it is not null, the "content-length" header will be set based
     *                     on the byte length of this string.
     * @throws IllegalStateException if a "respond" method has been called
     */
    default void respond(String contentType, String bodyAsString) {
        respond(contentType, bodyAsString, StandardCharsets.UTF_8);
    }
    /**
     * Send the response with a String body.
     *
     * @param contentType The content type for the body
     * @param bodyAsString The response body. If the value is null, then no response is sent.
     *                     If it is not null, the "content-length" header will be set based
     *                     on the byte length of this string.
     * @param charset The character encoding to use when converting body string to bytes
     * @throws IllegalStateException if a "respond" method has been called
     */
    void respond(String contentType, String bodyAsString, Charset charset);

    /**
     * Sets the response body as a byte[].
     *
     * @param contentType The content type for the body
     * @param bodyAsBytes The response body. If the value is null, then no response is sent.
     *                    If it is not null, the "content-length" header will be set based
     *                    on the length of this array.
     * @throws IllegalStateException if a "respond" method has been called
     */
    void respond(String contentType, byte[] bodyAsBytes);

    /**
     * Gets an OutputStream into which bytes can be written. The response is fully sent when the output stream is
     * closed. Use this form of the method when the content length is not known ahead of time.
     *
     * @param contentType The content type for the body
     * @return A reference to this response
     * @throws IOException If the underlying connection is closed
     * @throws IllegalStateException if a "respond" method has been called
     */
    OutputStream respond(String contentType) throws IOException;

    /**
     * Gets an OutputStream into which bytes can be written. The response is fully sent when the output stream is closed.
     *
     * @param contentType The content type for the body
     * @param contentLength The length of the content that will be written to the output stream in bytes
     * @return A reference to this response
     * @throws IOException If the underlying connection is closed
     * @throws IllegalStateException if a "respond" method has been called
     */
    OutputStream respond(String contentType, int contentLength) throws IOException;
}
