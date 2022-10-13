package com.hedera.hashgraph.web.grpc;

public record GrpcMethod(String name, GrpcRequestHandler handler) {

}
