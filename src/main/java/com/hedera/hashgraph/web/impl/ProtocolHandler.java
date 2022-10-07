package com.hedera.hashgraph.web.impl;

import com.hedera.hashgraph.web.WebRequest;

import java.nio.channels.SocketChannel;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Implements a protocol, such as HTTP or HTTP/2. Responsible for reading bytes from a {@link HttpInputStream}.
 */
public interface ProtocolHandler {
    void handle2(Dispatcher.ChannelData data, BiConsumer<Dispatcher.ChannelData, Dispatcher.RequestData> doDispatch);

    default void handleError(Dispatcher.ChannelData channelData, Dispatcher.RequestData reqData, RuntimeException ex) { }

    default void endOfRequest(Dispatcher.ChannelData channelData, Dispatcher.RequestData reqData) { }

    default void handleNoHandlerError(Dispatcher.ChannelData channelData, Dispatcher.RequestData reqData) { }
}
