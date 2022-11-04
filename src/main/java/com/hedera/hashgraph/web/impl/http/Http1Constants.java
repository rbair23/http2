package com.hedera.hashgraph.web.impl.http;

class Http1Constants {
    static final int MAX_METHOD_LENGTH = 1024;
    static final int MAX_URI_LENGTH = 2024;
    static final int MAX_VERSION_LENGTH = 8;
    static final int MAX_HEADER_KEY_LENGTH = 1024;
    static final int MAX_HEADER_VALUE_LENGTH = 1024;
    static final int MAX_CHUNK_SIZE_LENGTH = 1024;
    static final int MAX_CHUNK_FOOTERS_LENGTH = 1024;

    static final char CR = '\r';
    static final char LF = '\n';
    static final char SPACE = ' ';
    static final char COLON = ':';

    /** The last bit of HTTP2 preface after "PRI * HTTP/2.0" */
    static final byte[] HTTP2_PREFACE_END = "\r\nSM\r\n\r\n".getBytes();
}
