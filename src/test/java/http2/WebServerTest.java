package http2;

import com.hedera.hashgraph.web.WebServer;
import com.hedera.hashgraph.web.WebServerConfig;
import org.junit.jupiter.api.Test;

public class WebServerTest {
    @Test
    void simpleConstructor() {
        final var server = new WebServer();
        server.getRoutes().get("/random", (request, response) -> {
            // ...
        });
    }

    @Test
    void hostPortConstructor() {
        final var server = new WebServer("localhost", WebServer.EPHEMERAL_PORT);
        server.getRoutes().post("/echo", (request, response) -> {
            // ...
        });
    }

    @Test
    void simpleConfigConstructor() {
        final var server = new WebServer(new WebServerConfig.Builder()
                .host("localhost")
                .port(0)
                .backlog(120)
                .noDelay(true)
                .build());
        server.getRoutes().put("/store", (request, response) -> {
            // ...
        });
    }
}
