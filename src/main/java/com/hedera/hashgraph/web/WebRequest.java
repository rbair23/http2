package com.hedera.hashgraph.web;

public interface WebRequest extends AutoCloseable {
    WebHeaders getRequestHeaders();
}
