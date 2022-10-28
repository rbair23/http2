package com.hedera.hashgraph.web.impl.http2;

import com.twitter.hpack.Decoder;
import com.twitter.hpack.Encoder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class Http2HeaderCodec {
    private static final Set<String> VALID_REQUEST_PSEUDO_HEADERS = new HashSet<>(Arrays.asList(
            ":method",
            ":scheme",
            ":authority",
            ":path"));

    private static final Set<String> FORBIDDEN_REQUEST_HEADERS = new HashSet<>(Arrays.asList(
            "connection",
            "proxy-connection",
            "keep-alive",
            "transfer-encoding",
            "upgrade"));

    private final Encoder encoder;
    private final Decoder decoder;
    private final MeteredOutputStream metered = new MeteredOutputStream();

    public Http2HeaderCodec(final Encoder encoder, final Decoder decoder) {
        this.encoder = Objects.requireNonNull(encoder);
        this.decoder = Objects.requireNonNull(decoder);
    }

    public int encode(Http2Headers headers, OutputStream out) throws IOException {
        metered.init(out);

        // Pseudo headers are always written first
        for (final var key : headers.pseudoHeaderKeySet()) {
            encoder.encodeHeader(metered, key.getBytes(), headers.getPseudoHeader(key).getBytes(), false);
        }

        // Now the normal headers
        for (final var key : headers.keySet()) {
            encoder.encodeHeader(metered, key.getBytes(), headers.get(key).getBytes(), false);
        }

        final var length = metered.bytesWritten;
        metered.clear();
        return length;
    }

    public void decode(Http2Headers headers, InputStream headerData, final int streamId) throws IOException {
        if (headers != null) {
            headers.clear();
            final var pseudoHeadersDecoded = new AtomicBoolean(false);
            decoder.decode(headerData, (name, value, sensitive) -> {
                // Check for any headers that contained an uppercase character. If they do, then we have to throw.
                for (byte b : name) {
                    if (Character.isUpperCase(b)) {
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
                    }
                }

                // sensitive is a boolean
                final var headerName = new String(name);
                final var headerValue = new String(value);

                if (headerName.charAt(0) == ':') {
                    if (!VALID_REQUEST_PSEUDO_HEADERS.contains(headerName)) {
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
                    }

                    if (pseudoHeadersDecoded.get()) {
                        // Oof, we're getting pseudo-headers AFTER regular headers. No good.
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
                    }

                    // If the header has already been specified, it is an error
                    if (headers.putPseudoHeader(headerName, headerValue)) {
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
                    }
                } else {
                    pseudoHeadersDecoded.set(true);

                    if (FORBIDDEN_REQUEST_HEADERS.contains(headerName)) {
                        throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
                    }

                    if ("te".equals(headerName)) {
                        if (!"trailers".equals(headerValue)) {
                            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, streamId);
                        }
                    }

                    // TODO:
                        /*
                            Clients MUST NOT generate a request with a Host header field that differs from the ":authority"
                            pseudo-header field. A server SHOULD treat a request as malformed if it contains a Host header
                            field that identifies an entity that differs from the entity in the ":authority" pseudo-header
                            field. The values of fields need to be normalized to compare them (see Section 6.2 of
                            [RFC3986]). An origin server can apply any normalization method, whereas other servers MUST
                            perform scheme-based normalization (see Section 6.2.3 of [RFC3986]) of the two fields.

                            See the whole section 8.3.1 for more nuance and details
                         */
                    headers.put(headerName, headerValue);
                }
        });
        decoder.endHeaderBlock();
    }
}

    private static final class MeteredOutputStream extends OutputStream {
        private int bytesWritten = 0;
        private OutputStream wrapped;

        public void init(OutputStream wrapped) {
            this.bytesWritten = 0;
            this.wrapped = Objects.requireNonNull(wrapped);
        }

        public void clear() {
            this.bytesWritten = 0;
            this.wrapped = null;
        }

        @Override
        public void write(int b) throws IOException {
            wrapped.write(b);
            bytesWritten++;
        }

        @Override
        public void write(byte[] b) throws IOException {
            wrapped.write(b);
            bytesWritten += b.length;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            wrapped.write(b, off, len);
            bytesWritten += len;
        }

        @Override
        public void close() throws IOException {
            wrapped.close();
            this.bytesWritten = 0;
            this.wrapped = null;
        }

        @Override
        public void flush() throws IOException {
            wrapped.flush();
        }
    }
}
