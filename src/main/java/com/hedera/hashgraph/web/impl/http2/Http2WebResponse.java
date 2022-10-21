package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.ResponseAlreadySentException;
import com.hedera.hashgraph.web.StatusCode;
import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.WebResponse;
import com.hedera.hashgraph.web.impl.WebHeadersImpl;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

class Http2WebResponse implements WebResponse {
    private static final Http2Headers headers = new Http2Headers();
    private Http2Stream.OutputBufferOutputStream out;

    Http2WebResponse() {
    }

    @Override
    public WebHeaders getHeaders() {
        return headers;
    }

    @Override
    public WebResponse statusCode(StatusCode code) {
        headers.putStatus(code);
        return this;
    }

    @Override
    public WebResponse body(String bodyAsString) throws ResponseAlreadySentException {
        if (bodyAsString != null) {
            body(bodyAsString.getBytes());
        }
        return this;
    }

    @Override
    public WebResponse body(byte[] bodyAsBytes) throws ResponseAlreadySentException {
        if (bodyAsBytes != null && bodyAsBytes.length > 0) {
            out.write(bodyAsBytes, 0, bodyAsBytes.length);
        }
        return this;
    }

    @Override
    public OutputStream body() throws ResponseAlreadySentException, IOException {
        return out;
    }

    void reset(Http2Stream.OutputBufferOutputStream out) {
        headers.clear();
        this.out = out;
    }
}
