package com.hedera.hashgraph.web.impl.http;

import com.hedera.hashgraph.web.WebRoutes;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.HttpInputStream;
import com.hedera.hashgraph.web.impl.HttpOutputStream;
import com.hedera.hashgraph.web.impl.ProtocolHandler;
import com.hedera.hashgraph.web.impl.http2.Http2ProtocolHandler;

import java.util.Objects;
import java.util.function.BiConsumer;

public class HttpProtocolHandler implements ProtocolHandler {
    private final WebRoutes routes;
    private final Http2ProtocolHandler http2;

    public HttpProtocolHandler(WebRoutes routes, Http2ProtocolHandler http2ProtocolHandler) {
        this.routes = Objects.requireNonNull(routes);
        this.http2 = Objects.requireNonNull(http2ProtocolHandler);
    }

    @Override
    public void handle(Dispatcher.ChannelData data, BiConsumer<Dispatcher.ChannelData, Dispatcher.RequestData> doDispatch) {
        data.setProtocolHandler(http2);
        http2.handle(data, doDispatch);
    }

    @Override
    public void handleError(Dispatcher.ChannelData channelData, Dispatcher.RequestData reqData, RuntimeException ex) {

    }

    @Override
    public void endOfRequest(Dispatcher.ChannelData channelData, Dispatcher.RequestData reqData) {

    }

    @Override
    public void handleNoHandlerError(Dispatcher.ChannelData channelData, Dispatcher.RequestData reqData) {

    }
}
