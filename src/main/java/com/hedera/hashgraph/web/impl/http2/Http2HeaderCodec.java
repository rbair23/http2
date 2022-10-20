package com.hedera.hashgraph.web.impl.http2;

import com.twitter.hpack.Decoder;
import com.twitter.hpack.Encoder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

public class Http2HeaderCodec {
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

    public void decode(Http2Headers headers, InputStream headerData) throws IOException {
        if (headers != null) {
            headers.clear();
        }

        decoder.decode(headerData, (name, value, sensitive) -> {
            if (headers != null) {
                // sensitive is a boolean
                final var headerName = new String(name);
                final var headerValue = new String(value);

                if (headerName.charAt(0) == ':') {
                    headers.putPseudoHeader(headerName, headerValue);
                } else {
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
            }
        });
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
