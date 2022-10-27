package http2;

import com.hedera.hashgraph.web.*;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class EchoTest {
    @Test
    void echo() throws IOException, InterruptedException {
        final var server = new WebServer(new WebServerConfig.Builder()
                .host("localhost")
                .port(8080) //WebServer.EPHEMERAL_PORT);
                .executor(Executors.newCachedThreadPool())
                .build());
        server.getRoutes().get("/echo", (req, response) -> {
            assertEquals("GET", req.getMethod());
            assertEquals(HttpVersion.HTTP_2, req.getVersion());
            assertEquals("/echo", req.getPath());

            final var responseBody = "Hello World!";
            response
                    .statusCode(StatusCode.OK_200)
                    .header("Content-Length", "" + responseBody.length())
                    .header("Server", "EchoTest")
                    .respond(WebResponse.CONTENT_TYPE_PLAIN_TEXT, responseBody);
        });
        server.getRoutes().get("/", (req, res) -> {
            res.statusCode(StatusCode.OK_200)
                    .respond("text/plain", "I am sending some bytes");
        });
        server.getRoutes().post("/", (req, res) -> {
            final byte[] data = req.getRequestBody().readAllBytes();
            System.out.println("READ: " + data.length + " bytes");
            res.respond(StatusCode.OK_200);
        });
        server.start();

//        final var port = server.getBoundAddress().getPort();
//        System.out.println("port: " + port);
//
//        final var client = new OkHttpClient.Builder()
//                .followRedirects(false)
//                .callTimeout(0, TimeUnit.MILLISECONDS)
//                .readTimeout(0, TimeUnit.MILLISECONDS)
//                .writeTimeout(0, TimeUnit.MILLISECONDS)
//                .connectTimeout(0, TimeUnit.MILLISECONDS)
//                .protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE))
//                .retryOnConnectionFailure(false)
//                .build();
//
//        final var request = new Request.Builder()
//                .url("http://localhost:" + port + "/echo")
//                .get()
//                .build();
//
//        for (int i = 0; i < 250000; i++) {
//            try (final var response = client.newCall(request).execute()){
//                final var body = response.body();
//                if (body != null) {
//                    body.bytes();
//                }
//
////                if (i % 100 == 0) {
//                    System.out.println("Completed " + i + " of 250000");
////                }
//            } catch (Exception e) {
//                e.printStackTrace();
//            }
//        }


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



//        server.stop(Duration.ofSeconds(1));
        while (true) {
            Thread.sleep(1000);
        }
    }
}
