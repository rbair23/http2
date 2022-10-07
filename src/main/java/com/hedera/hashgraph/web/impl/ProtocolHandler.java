package com.hedera.hashgraph.web.impl;

import com.hedera.hashgraph.web.WebRequest;

import java.nio.channels.SocketChannel;
import java.util.function.Consumer;

/**
 * Implements a protocol, such as HTTP or HTTP/2. Responsible for reading bytes from a {@link HttpInputStream}.
 */
public interface ProtocolHandler {
    default void handle(HttpInputStream in, HttpOutputStream out) { }


    default void handle2(Dispatcher.RequestData requestData, Consumer<Dispatcher.RequestData> doDispatch) {
    }
}
