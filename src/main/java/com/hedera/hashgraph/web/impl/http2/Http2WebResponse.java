package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.StatusCode;
import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.WebResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

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
    public void respond(StatusCode code) {
        statusCode(code);
    }

    @Override
    public void respond(String contentType, String bodyAsString, Charset charset) {
        if (bodyAsString != null) {
            respond(contentType, bodyAsString.getBytes(charset));
        }
    }

    @Override
    public void respond(String contentType, byte[] bodyAsBytes)  {
        contentType(contentType);
        if (bodyAsBytes != null && bodyAsBytes.length > 0) {
            out.write(bodyAsBytes, 0, bodyAsBytes.length);
        }
    }


    @Override
    public OutputStream respond(String contentType) throws IOException {
        contentType(contentType);
        return out;
    }

    @Override
    public OutputStream respond(String contentType, int contentLength) throws IOException {
        contentType(contentType);
        // TODO what should I do with contentLength?
        return out;
    }

    void reset(Http2Stream.OutputBufferOutputStream out) {
        headers.clear();
        this.out = out;
    }
}
