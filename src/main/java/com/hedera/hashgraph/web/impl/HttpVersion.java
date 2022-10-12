package com.hedera.hashgraph.web.impl;

import java.nio.charset.StandardCharsets;

public enum HttpVersion {
    HTTP_1("HTTP/1.0"),
    HTTP_1_1("HTTP/1.1"),
    HTTP_2("HTTP/2.0");

    private final String versionString;
    private final byte[] versionStringBytes;

    HttpVersion(String versionString) {
        this.versionString = versionString;
        this.versionStringBytes = versionString.getBytes(StandardCharsets.US_ASCII);
    }

    public String versionString() {
        return versionString;
    }
}
