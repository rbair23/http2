package com.hedera.hashgraph.web.grpc;

import java.util.Objects;

public class GrpcException extends RuntimeException {
    private final GrpcStatus status;

    public GrpcException(GrpcStatus status) {
        this.status = Objects.requireNonNull(status);
    }

    public GrpcException(String message, GrpcStatus status) {
        super(message);
        this.status = Objects.requireNonNull(status);
    }

    public GrpcException(String message, Throwable cause, GrpcStatus status) {
        super(message, cause);
        this.status = Objects.requireNonNull(status);
    }

    public GrpcException(Throwable cause, GrpcStatus status) {
        super(cause);
        this.status = Objects.requireNonNull(status);
    }

    public GrpcException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace, GrpcStatus status) {
        super(message, cause, enableSuppression, writableStackTrace);
        this.status = Objects.requireNonNull(status);
    }
}
