package com.hedera.hashgraph.web.impl;

import java.util.function.BiConsumer;

/**
 * Implements a protocol, such as HTTP or HTTP/2. Responsible for reading bytes from a {@link HttpInputStream}.
 */
public interface ProtocolHandler {
    void handle(Dispatcher.ChannelData data, BiConsumer<Dispatcher.ChannelData, WebRequestImpl> doDispatch);

    void onServerError(Dispatcher.ChannelData channelData, WebRequestImpl request, RuntimeException ex);

    void onEndOfRequest(Dispatcher.ChannelData channelData, WebRequestImpl request);

    void on404(Dispatcher.ChannelData channelData, WebRequestImpl request);
}
