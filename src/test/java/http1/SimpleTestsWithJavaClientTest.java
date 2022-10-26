package http1;

import com.hedera.hashgraph.web.WebResponse;
import com.hedera.hashgraph.web.WebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings({"SameParameterValue", "ConstantConditions"})
class SimpleTestsWithJavaClientTest {
    @Test
    void test404() throws Exception {
        WebServer server = new WebServer("localhost", 12345);
        server.start();
        // test url
        final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        final HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(new URI("http:/" + server.getBoundAddress() + "/BAD_PATH"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, response.statusCode());
        // stop server
        server.stop(Duration.ofSeconds(1));
    }

    @Test
    void testSimpleGet() throws Exception {
        // create server
        final String responseString = "Hello You";
        WebServer server = new WebServer("localhost", WebServer.EPHEMERAL_PORT);
        server.getRoutes().get("/hello", (request, response) ->
                response.respond(WebResponse.CONTENT_TYPE_PLAIN_TEXT, responseString));
        server.start();
        // test url
        final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        final HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(new URI("http:/" + server.getBoundAddress() + "/hello"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals(responseString, response.body());
        // stop server
        server.stop(Duration.ofSeconds(1));
    }

    @Test
    void testHttp11TwoGets() throws Exception {
        // create server
        final String responseString = "Hello You";
        WebServer server = new WebServer("localhost", WebServer.EPHEMERAL_PORT);
        server.getRoutes().get("/hello", (request, response) ->
                response.respond(WebResponse.CONTENT_TYPE_PLAIN_TEXT, responseString));
        server.start();
        // test url
        final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        {
            final HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(new URI("http:/" + server.getBoundAddress() + "/hello"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(responseString, response.body());
        }
        System.out.println("Request 1 done");
        // test url again
        {
            final HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(new URI("http:/" + server.getBoundAddress() + "/hello"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertEquals(responseString, response.body());
        }
        // stop server
        server.stop(Duration.ofSeconds(1));
    }

    @ParameterizedTest // TODO test empty string
    @ValueSource(strings = {" ", "Hello World!", """
            This is a multi line test
            \r\n with special chars \t \u1827
            """})
    void testEcho(String testString) throws Exception {
        WebServer server = new WebServer("localhost", WebServer.EPHEMERAL_PORT);
        server.getRoutes().post("/echo", (request, response) -> {
            final int contentSize = request.getRequestHeaders().getContentLength();
            System.out.println("contentSize = " + contentSize);
            final InputStream in = request.getRequestBody();
            byte[] contentBytes = new byte[contentSize];
            int bytesRead = in.read(contentBytes);
            assertEquals(contentSize, bytesRead);
            in.close();
            System.out.println("contentBytes = [" + new String(contentBytes)+"]");
            // create response
            response.respond(request.getRequestHeaders().getContentType(), contentBytes);
        });
        server.start();
        // test url
        final HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        final HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(new URI("http:/" + server.getBoundAddress() + "/echo"))
                        .POST(HttpRequest.BodyPublishers.ofString(testString))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals(testString, response.body());
        // stop server
        server.stop(Duration.ofSeconds(1));
    }

}
