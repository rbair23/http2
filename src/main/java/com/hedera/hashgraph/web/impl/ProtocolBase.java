package com.hedera.hashgraph.web.impl;

import java.io.IOException;
import java.util.function.BiConsumer;

/**
 * Implements a protocol, such as HTTP or HTTP/2. Responsible for reading bytes from a {@link HttpInputStream}.
 */
public abstract class ProtocolBase {
    protected abstract void handle(Dispatcher.ChannelData data, BiConsumer<Dispatcher.ChannelData, WebRequestImpl> doDispatch);

    protected abstract void onServerError(Dispatcher.ChannelData channelData, WebRequestImpl request, RuntimeException ex);

    protected abstract void onEndOfRequest(Dispatcher.ChannelData channelData, WebRequestImpl request);

    protected abstract void on404(Dispatcher.ChannelData channelData, WebRequestImpl request);

    protected abstract void flush(Dispatcher.ChannelData channelData, Dispatcher.RequestData requestData) throws IOException;
}
