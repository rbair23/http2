package com.hedera.hashgraph.web.grpc;

import java.util.*;

public record GrpcService(String name, Map<String, GrpcMethod> methods) {
    public GrpcService {
        Objects.requireNonNull(name);
        Objects.requireNonNull(methods);
    }

    // TODO Need to make a defensive copy of methods, either on ingest or query

    public GrpcMethod method(final String methodName) {
        return methods.get(methodName);
    }

    public static final class Builder {
        private final String name;
        private Map<String, GrpcMethod> methods;

        public Builder(String name) {
            this.name = Objects.requireNonNull(name);
        }

        public Builder method(GrpcMethod method) {
            Objects.requireNonNull(method);
            this.methods.put(method.name(), method);
            return this;
        }

        public Builder method(String name, GrpcRequestHandler handler) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(handler);
            this.methods.put(name, new GrpcMethod(name, handler));
            return this;
        }

        public GrpcService build() {
            return new GrpcService(name, new HashMap<>(methods));
        }
    }
}
