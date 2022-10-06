package com.hedera.hashgraph.web.impl;

/**
 * Implements a protocol, such as HTTP or HTTP/2. Responsible for reading bytes from a {@link HttpInputStream}.
 */
public interface ProtocolHandler {
    void handle(HttpInputStream in, HttpOutputStream out);
}
