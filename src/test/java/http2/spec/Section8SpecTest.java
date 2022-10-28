package http2.spec;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Headers;
import com.hedera.hashgraph.web.impl.http2.frames.DataFrame;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("Section 8 :: HTTP Message Exchanges")
@Tag("8")
public class Section8SpecTest {

    @Nested
    @DisplayName("Section 8.1 :: HTTP Request/Response Exchange")
    @Tags({@Tag("8"), @Tag("8.1")})
    final class DataFrameTest extends SpecTest {
        /**
         * An endpoint that receives a HEADERS frame without the END_STREAM flag set after receiving a final
         * (non-informational) status code MUST treat the corresponding request or response as malformed
         * (Section 8.1.2.6).
         */
        @Test
        @DisplayName("Sends a second HEADERS frame without the END_STREAM flag")
        void sendDataFrameWithStream0() throws IOException {
            client.handshake()
                    .sendHeaders(true, false, 1, createCommonHeaders().putMethod("POST"))
                    .sendData(false, 1, randomString(8))
                    .sendHeaders(true, false, 1, createDummyHeaders(1));

            verifyStreamError(Http2ErrorCode.PROTOCOL_ERROR);
        }
    }

    @Nested
    @DisplayName("Section 8.1.2 :: HTTP Header Fields")
    @Tags({@Tag("8"), @Tag("8.1"), @Tag("8.1.2")})
    final class HttpHeaderFieldTest extends SpecTest {
        /**
         * Just as in HTTP/1.x, header field names are strings of ASCII characters that are compared in a
         * case-insensitive fashion. However, header field names MUST be converted to lowercase prior to their
         * encoding in HTTP/2. A request or response containing uppercase header field names MUST be treated as
         * malformed (Section 8.1.2.6).
         */
        @Test
        @DisplayName("Sends a HEADERS frame that contains the header field name in uppercase letters")
        void sendUppercaseHeaderName() throws IOException {
            final var headers = createCommonHeadersList();
            headers.add("X-TEST");
            headers.add("ok");
            client.handshake()
                    .sendHeaders(true, true, 1, headers);

            verifyStreamError(Http2ErrorCode.PROTOCOL_ERROR);
        }
    }

    @Nested
    @DisplayName("Section 8.1.2.1 :: Pseudo Header Fields")
    @Tags({@Tag("8"), @Tag("8.1"), @Tag("8.1.2"), @Tag("8.1.2.1")})
    final class PseudoHeaderFieldTest extends SpecTest {
        /**
         * Pseudo-header fields are only valid in the context in which they are defined. Pseudo-header fields
         * defined for requests MUST NOT appear in responses; pseudo-header fields defined for responses MUST NOT
         * appear in requests. Pseudo-header fields MUST NOT appear in trailers. Endpoints MUST treat a request or
         * response that contains undefined or invalid pseudo-header fields as malformed (Section 8.1.2.6).
         */
        @Test
        @DisplayName("Sends a HEADERS frame that contains a unknown pseudo-header field")
        void sendUnknownPseudoHeader() throws IOException {
            final var headers = createCommonHeadersList();
            headers.add(":test");
            headers.add("ok");
            client.handshake()
                    .sendHeaders(true, true, 1, headers);

            verifyStreamError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * Pseudo-header fields are only valid in the context in which they are defined. Pseudo-header fields
         * defined for requests MUST NOT appear in responses; pseudo-header fields defined for responses MUST NOT
         * appear in requests. Pseudo-header fields MUST NOT appear in trailers. Endpoints MUST treat a request or
         * response that contains undefined or invalid pseudo-header fields as malformed (Section 8.1.2.6).
         */
        @Test
        @DisplayName("Sends a HEADERS frame that contains the pseudo-header field defined for response")
        void sendResponsePseudoHeaderInRequest() throws IOException {
            final var headers = createCommonHeadersList();
            headers.add(":status");
            headers.add("200");
            client.handshake()
                    .sendHeaders(true, true, 1, headers);

            verifyStreamError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * Pseudo-header fields are only valid in the context in which they are defined. Pseudo-header fields
         * defined for requests MUST NOT appear in responses; pseudo-header fields defined for responses MUST NOT
         * appear in requests. Pseudo-header fields MUST NOT appear in trailers. Endpoints MUST treat a request or
         * response that contains undefined or invalid pseudo-header fields as malformed (Section 8.1.2.6).
         */
        @Test
        @DisplayName("Sends a HEADERS frame that contains a pseudo-header field as trailers")
        void sendPseudoHeaderAsTrailer() throws IOException {
            final var trailers = new Http2Headers();
            trailers.put(":method", "200");
            client.handshake()
                    .sendHeaders(true, false, 1, createCommonHeaders().putMethod("POST"))
                    .sendData(false, 1, randomString(14))
                    .sendHeaders(true, false, 1, trailers);

            verifyStreamError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * All pseudo-header fields MUST appear in the header block before regular header fields. Any request or
         * response that contains a pseudo-header field that appears in a header block after a regular header field
         * MUST be treated as malformed (Section 8.1.2.6).
         */
        @Test
        @DisplayName("Sends a HEADERS frame that contains a pseudo-header field that appears in a header block after a regular header field")
        void sendPseudoHeadersAfterNormalHeaders() throws IOException {
            final var headers = createCommonHeadersList();
            headers.add(0, "x-test");
            headers.add(1, "ok");
            client.handshake()
                    .sendHeaders(true, true, 1, headers);

            verifyStreamError(Http2ErrorCode.PROTOCOL_ERROR);
        }
    }

    @Nested
    @DisplayName("Section 8.1.2.2 :: Connection-Specific Header Fields")
    @Tags({@Tag("8"), @Tag("8.1"), @Tag("8.1.2"), @Tag("8.1.2.2")})
    final class ConnectionSpecificHeaderFieldTest extends SpecTest {
        /**
         * An endpoint MUST NOT generate an HTTP/2 message containing connection-specific header fields; any message
         * containing connection-specific header fields MUST be treated as malformed (Section 8.1.2.6).
         */
        @ParameterizedTest(name = "Invalid header {0}")
        @CsvSource(textBlock = """
                connection, keep-alive
                proxy-connection, keep-alive
                keep-alive, max=30
                transfer-encoding, gzip
                upgrade, foo/2
                """)
        @DisplayName("Sends a HEADERS frame that contains the connection-specific header field")
        void sendConnectionSpecificHeaderField(String key, String value) throws IOException {
            final var headers = createCommonHeaders();
            headers.put(key, value);
            client.handshake()
                    .sendHeaders(true, true, 1, headers);

            verifyStreamError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * The only exception to this is the TE header field, which MAY be present in an HTTP/2 request; when it is,
         * it MUST NOT contain any value other than "trailers".
         */
        @Test
        @DisplayName("Sends a HEADERS frame that contains the TE header field with any value other than \"trailers\"")
        void sendTEHeaderFieldWithBadValue() throws IOException {
            final var headers = createCommonHeadersList();
            headers.add("trailers");
            headers.add("test");
            headers.add("te");
            headers.add("trailers, deflate");
            client.handshake()
                    .sendHeaders(true, true, 1, headers);

            verifyStreamError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * The only exception to this is the TE header field, which MAY be present in an HTTP/2 request; when it is,
         * it MUST NOT contain any value other than "trailers".
         */
        @Test
        @DisplayName("Sends a HEADERS frame that contains the TE header field with value \"trailers\"")
        void sendTEHeaderFieldWithOKValue() throws IOException {
            final var headers = createCommonHeadersList();
            headers.add("trailers");
            headers.add("test");
            headers.add("te");
            headers.add("trailers");
            client.handshake()
                    .sendHeaders(true, true, 1, headers);

            final var frame = client.awaitFrame(DataFrame.class);
            assertNotNull(frame);
        }
    }

    @Nested
    @DisplayName("Section 8.1.2.3 :: Request Pseudo-Header Fields")
    @Tags({@Tag("8"), @Tag("8.1"), @Tag("8.1.2"), @Tag("8.1.2.3")})
    final class RequestPseudoHeaderFieldTest extends SpecTest {
        /**
         * The ":path" pseudo-header field includes the path and query parts of the target URI (the "path-absolute"
         * production and optionally a '?' character followed by the "query" production (see Sections 3.3 and 3.4
         * of [RFC3986])). A request in asterisk form includes the value '*' for the ":path" pseudo-header field.
         *
         * <p>This pseudo-header field MUST NOT be empty for "http" or "https" URIs; "http" or "https" URIs that do not
         * contain a path component MUST include a value of '/'.
         */
        @Test
        @DisplayName("Sends a HEADERS frame with empty \":path\" pseudo-header field")
        void sendEmptyPath() throws IOException {
            final var headers = createCommonHeaders();
            headers.putPseudoHeader(":path", "");
            client.handshake()
                    .sendHeaders(true, true, 1, headers);

            verifyStreamError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * All HTTP/2 requests MUST include exactly one valid value for the ":method", ":scheme", and ":path"
         * pseudo-header fields, unless it is a CONNECT request (Section 8.3).
         */
        @ParameterizedTest(name = "Omit the {0} header")
        @ValueSource(strings = { ":method", ":scheme", ":path" })
        @DisplayName("Sends a HEADERS frame that omits a required pseudo-header field")
        void sendEmptyPseudoHeader(String header) throws IOException {
            final var headers = createCommonHeadersList();
            final var index = headers.indexOf(header);
            headers.remove(index + 1);
            headers.remove(index);
            client.handshake()
                    .sendHeaders(true, true, 1, headers);

            verifyStreamError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * All HTTP/2 requests MUST include exactly one valid value for the ":method", ":scheme", and ":path"
         * pseudo-header fields, unless it is a CONNECT request (Section 8.3).
         */
        @ParameterizedTest(name = "Duplicate the {0} header")
        @ValueSource(strings = { ":method", ":scheme", ":path" })
        @DisplayName("Sends a HEADERS frame that omits a required pseudo-header field")
        void sendDuplicatePseudoHeader(String header) throws IOException {
            final var headers = createCommonHeadersList();
            final var index = headers.indexOf(header);
            headers.add(index + 2, header);
            headers.add(index + 3, headers.get(index + 1));
            client.handshake()
                    .sendHeaders(true, true, 1, headers);

            verifyStreamError(Http2ErrorCode.PROTOCOL_ERROR);
        }
    }

    @Nested
    @DisplayName("Section 8.1.2.6 :: Malformed Requests and Responses")
    @Tags({@Tag("8"), @Tag("8.1"), @Tag("8.1.2"), @Tag("8.1.2.6")})
    final class MalformedRequestAndResponseTest extends SpecTest {
        /**
         * A request or response that includes a payload body can include a content-length header field. A request or
         * response is also malformed if the value of a content-length header field does not equal the sum of the
         * DATA frame payload lengths that form the body. A response that is defined to have no payload, as described
         * in [RFC7230], Section 3.3.2, can have a non-zero content-length header field, even though no content is
         * included in DATA frames.
         *
         * <p>Intermediaries that process HTTP requests or responses (i.e., any intermediary not acting as a tunnel)
         * MUST NOT forward a malformed request or response. Malformed requests or responses that are detected MUST be
         * treated as a stream error (Section 5.4.2) of type PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("Sends a HEADERS frame with the \"content-length\" header field which does not equal the DATA frame payload length")
        void sendInvalidContentLength() throws IOException {
            final var headers = createCommonHeaders();
            headers.putMethod("POST");
            headers.put("content-length", "1");
            client.handshake()
                    .sendHeaders(true, false, 1, headers)
                    .sendData(true, 1, randomString(4));

            verifyStreamError(Http2ErrorCode.PROTOCOL_ERROR);
        }

        /**
         * A request or response that includes a payload body can include a content-length header field. A request or
         * response is also malformed if the value of a content-length header field does not equal the sum of the
         * DATA frame payload lengths that form the body. A response that is defined to have no payload, as described
         * in [RFC7230], Section 3.3.2, can have a non-zero content-length header field, even though no content is
         * included in DATA frames.
         *
         * <p>Intermediaries that process HTTP requests or responses (i.e., any intermediary not acting as a tunnel)
         * MUST NOT forward a malformed request or response. Malformed requests or responses that are detected MUST be
         * treated as a stream error (Section 5.4.2) of type PROTOCOL_ERROR.
         */
        @Test
        @DisplayName("Sends a HEADERS frame with the \"content-length\" header field which does not equal the sum of the multiple DATA frames payload length")
        void sendInvalidContentLengthWithMultipleData() throws IOException {
            final var headers = createCommonHeaders();
            headers.putMethod("POST");
            headers.put("content-length", "10");
            client.handshake()
                    .sendHeaders(true, false, 1, headers)
                    .sendData(true, 1, randomString(8))
                    .sendData(true, 1, randomString(3));

            verifyStreamError(Http2ErrorCode.PROTOCOL_ERROR);
        }
    }
}
