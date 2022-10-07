package com.hedera.hashgraph.web.impl;

import java.util.function.BiConsumer;

/**
 * Implements a protocol, such as HTTP or HTTP/2. Responsible for reading bytes from a {@link HttpInputStream}.
 */
public interface ProtocolHandler {
    void handle(Dispatcher.ChannelData data, BiConsumer<Dispatcher.ChannelData, Dispatcher.RequestData> doDispatch);

    void handleError(Dispatcher.ChannelData channelData, Dispatcher.RequestData reqData, RuntimeException ex);

    void endOfRequest(Dispatcher.ChannelData channelData, Dispatcher.RequestData reqData);

    void handleNoHandlerError(Dispatcher.ChannelData channelData, Dispatcher.RequestData reqData);
}
