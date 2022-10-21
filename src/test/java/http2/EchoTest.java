package http2;

import com.hedera.hashgraph.web.*;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class EchoTest {
    @Test
    void echo() throws IOException {
        final var server = new WebServer("localhost", WebServer.EPHEMERAL_PORT);
        server.getRoutes().get("/echo", req -> {
            assertEquals("GET", req.getMethod());
            assertEquals(HttpVersion.HTTP_2, req.getVersion());
            assertEquals("/echo", req.getPath());

            final var responseBody = "Hello World!";
            req.getResponse()
                    .statusCode(StatusCode.OK_200)
                    .header("Content-Length", "" + responseBody.length())
                    .header("Server", "EchoTest")
                    .body(responseBody);
        });
        server.start();

        final var port = server.getBoundAddress().getPort();
        System.out.println("port: " + port);

        final var client = new OkHttpClient.Builder()
                .followRedirects(false)
                .callTimeout(Duration.ofMinutes(10))
                .protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE))
                .retryOnConnectionFailure(false)
                .build();

        final var request = new Request.Builder()
                .url("http://localhost:" + port + "/echo")
                .get()
                .build();

        for (int i = 0; i < 250; i++) {
            try {
                final var response = client.newCall(request).execute();
                System.out.println(response.body().string());
                Thread.sleep(1000);
            } catch (Exception e) {
//                throw new RuntimeException(e);
            }
        }


//        final var client = HttpClient.newHttpClient();
//        final var req = HttpRequest.newBuilder()
//                .uri(new URI("http", "", "localhost", port, "/echo", "", ""))
//                .version(HttpClient.Version.HTTP_2)
//                .timeout(Duration.ofMillis(200))
//                .GET()
//                .build();
//
//        final var response = client.sendAndReceive(req, HttpResponse.BodyHandlers.ofString());
//        System.out.println(response.body());



        server.stop(Duration.ofSeconds(1));
    }
}
