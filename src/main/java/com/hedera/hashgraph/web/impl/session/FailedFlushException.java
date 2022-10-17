package com.hedera.hashgraph.web.impl.session;

public class FailedFlushException extends RuntimeException {
    public FailedFlushException(String message, Throwable cause) {
        super(message, cause);
    }
}
