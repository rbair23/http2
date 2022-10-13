package com.hedera.hashgraph.web.grpc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface GrpcRequest {
    InputStream protoRequestStream() throws IOException;
    OutputStream startResponse(GrpcStatus status) throws IOException;
    default void respond(GrpcStatus status) throws IOException {
        final var out = startResponse(status);

        // Close it safely
        try {
            out.close();
        } catch (IOException ignored) {
        }
    }
}
