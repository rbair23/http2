package com.hedera.hashgraph.web;

import java.io.IOException;

public interface WebRequestHandler {
    void handle(WebRequest request) throws IOException;
}
