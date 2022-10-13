package com.hedera.hashgraph.web.grpc;

import java.io.IOException;

public interface GrpcRequestHandler {
    void handle(final GrpcRequest request) throws GrpcException, IOException;
}
