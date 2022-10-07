package com.hedera.hashgraph.web;

public record WebRoute(String method, String path, WebRequestHandler handler) {
    // TODO Make sure none of the above are null.
}
