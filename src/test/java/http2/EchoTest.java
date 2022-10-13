package http2;

import com.hedera.hashgraph.web.ResponseAlreadySentException;
import com.hedera.hashgraph.web.StatusCode;
import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.WebServer;
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
        WebServer server = new WebServer("localhost", WebServer.EPHEMERAL_PORT);
        server.getRoutes().get("/echo", req -> {
            assertEquals("GET", req.getMethod());
            assertEquals("HTTP/2.0", req.getVersion());
            assertEquals("/echo", req.getPath());

            final var responseBody = "Hello World!";
            final var responseHeaders = new WebHeaders()
                    .setContentLength(responseBody.length())
                    .setContentEncoding(WebHeaders.CONTENT_ENCODING_GZIP)
                    .setServer("EchoTest");

            try (var out = req.startResponse(StatusCode.OK_200, responseHeaders)) {
                out.write(responseBody.getBytes());
            } catch (IOException | ResponseAlreadySentException exception) {
                fail(exception);
            }
        });
        server.start();

        final var port = server.getBoundAddress().getPort();
        System.out.println("port: " + port);

        final var client = new OkHttpClient.Builder()
                .followRedirects(false)
                .callTimeout(Duration.ofMillis(2000000))
                .protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE))
                .retryOnConnectionFailure(false)
                .build();

        final var request = new Request.Builder()
                .url("http://localhost:" + port + "/echo")
                .get()
                .build();

        final var response = client.newCall(request).execute();
        System.out.println(response.body().string());


//        final var client = HttpClient.newHttpClient();
//        final var req = HttpRequest.newBuilder()
//                .uri(new URI("http", "", "localhost", port, "/echo", "", ""))
//                .version(HttpClient.Version.HTTP_2)
//                .timeout(Duration.ofMillis(200))
//                .GET()
//                .build();
//
//        final var response = client.send(req, HttpResponse.BodyHandlers.ofString());
//        System.out.println(response.body());



        server.stop(Duration.ofSeconds(1));
    }
    @Test
    void echoHttp1() throws IOException, URISyntaxException, InterruptedException {
        WebServer server = new WebServer("localhost", WebServer.EPHEMERAL_PORT);
        server.start();

        final var port = server.getBoundAddress().getPort();
        System.out.println("port: " + port);

        final var client = new OkHttpClient.Builder()
                .followRedirects(false)
                .callTimeout(Duration.ofMillis(20000))
//                .protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE))
                .retryOnConnectionFailure(false)
                .build();

        final var request = new Request.Builder()
                .url("http://localhost:" + port + "/echo")
                .get()
                .build();

        final var response = client.newCall(request).execute();
        System.out.println(response.body().string());


//        final var client = HttpClient.newHttpClient();
//        final var req = HttpRequest.newBuilder()
//                .uri(new URI("http", "", "localhost", port, "/echo", "", ""))
//                .version(HttpClient.Version.HTTP_2)
//                .timeout(Duration.ofMillis(200))
//                .GET()
//                .build();
//
//        final var response = client.send(req, HttpResponse.BodyHandlers.ofString());
//        System.out.println(response.body());



        server.stop(Duration.ofSeconds(1));
    }
}
