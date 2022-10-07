package com.hedera.hashgraph.web.impl.http;

import com.hedera.hashgraph.web.WebRoutes;
import com.hedera.hashgraph.web.impl.HttpInputStream;
import com.hedera.hashgraph.web.impl.HttpOutputStream;
import com.hedera.hashgraph.web.impl.ProtocolHandler;
import com.hedera.hashgraph.web.impl.http2.Http2ProtocolHandler;

public class HttpProtocolHandler implements ProtocolHandler {
    public HttpProtocolHandler(WebRoutes routes, Http2ProtocolHandler http2ProtocolHandler) {

    }

    @Override
    public void handle(HttpInputStream in, HttpOutputStream out) {

    }
}
