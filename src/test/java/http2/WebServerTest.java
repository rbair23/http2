package http2;

import com.hedera.hashgraph.web.WebRequest;
import com.hedera.hashgraph.web.WebRequestHandler;
import com.hedera.hashgraph.web.WebServer;
import com.hedera.hashgraph.web.WebServerConfig;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

public class WebServerTest {
    @Test
    void simpleConstructor() {
        final var server = new WebServer();
        server.getRoutes().get("/random", request -> {
            // ...
        });
    }

    @Test
    void hostPortConstructor() {
        final var server = new WebServer("localhost", WebServer.EPHEMERAL_PORT);
        server.getRoutes().post("/echo", request -> {
            // ...
        });
    }

    @Test
    void simpleConfigConstructor() {
        final var server = new WebServer(new WebServerConfig.Builder()
                .host("localhost")
                .port(0)
                .backlog(120)
                .build());
        server.getRoutes().put("/store", request -> {
            // ...
        });
    }
}
