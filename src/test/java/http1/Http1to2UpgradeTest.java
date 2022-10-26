package http1;

import com.hedera.hashgraph.web.WebResponse;
import com.hedera.hashgraph.web.WebServer;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests using h2c upgrade mechanism
 */
class Http1to2UpgradeTest {

    @Test
    void testSimpleGet() throws Exception {
        // create server
        final String responseString = "Hello You";
        WebServer server = new WebServer("localhost", WebServer.EPHEMERAL_PORT);
        server.getRoutes().get("/hello", (request, response) ->
                response.respond(WebResponse.CONTENT_TYPE_PLAIN_TEXT, responseString));
        server.start();
        // test url
        final HttpClient httpClient = HttpClient.newHttpClient();
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
    void testTwoGets() throws Exception {
        // create server
        final String responseString = "Hello You";
        WebServer server = new WebServer("localhost", WebServer.EPHEMERAL_PORT);
        server.getRoutes().get("/hello", (request, response) ->
                response.respond(WebResponse.CONTENT_TYPE_PLAIN_TEXT, responseString));
        server.start();
        // test url
        final HttpClient httpClient = HttpClient.newHttpClient();
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
}
