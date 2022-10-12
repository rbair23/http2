package com.hedera.hashgraph.web.impl;

import com.hedera.hashgraph.web.impl.session.ConnectionContext;

import java.io.IOException;
import java.util.function.BiConsumer;

/**
 * Implements a protocol, such as HTTP or HTTP/2. Responsible for reading bytes from a {@link HttpInputStream}.
 */
public abstract class ProtocolBase {
    protected abstract void handle(ConnectionContext data, BiConsumer<ConnectionContext, WebRequestImpl> doDispatch);

    protected abstract void onServerError(ConnectionContext channelData, WebRequestImpl request, RuntimeException ex);

    protected abstract void onEndOfRequest(ConnectionContext channelData, WebRequestImpl request);

    protected abstract void on404(ConnectionContext channelData, WebRequestImpl request);

    protected abstract void flush(ConnectionContext channelData, RequestSession requestData) throws IOException;
}
