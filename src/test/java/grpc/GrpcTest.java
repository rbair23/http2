package grpc;

import com.hedera.hashgraph.web.WebServer;
import com.hedera.hashgraph.web.grpc.GrpcRouter;
import com.hedera.hashgraph.web.grpc.GrpcService;
import com.hedera.hashgraph.web.grpc.GrpcStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;

class GrpcTest {
    @Test
    void whatever() throws IOException {
        final var server = new WebServer("localhost", WebServer.EPHEMERAL_PORT);
        final var grpc = new GrpcRouter("/grpc", server.getRoutes());
        grpc.service(new GrpcService.Builder("myService")
                .method("foo", request -> {
                    try (final var in = request.protoRequestStream()) {
                        // Using this input stream, parse the appropriate type of object out.
                        // Then..
                    }

                    try (final var out = request.startResponse(GrpcStatus.OK)) {
                        // Using this output stream, write in the protobuf bytes
                        // Then we're done!!
                    }
                })
                .method("bar", request -> {})
                .method("baz", request -> {}));
    }
}
