package http1;

import com.hedera.hashgraph.web.*;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@SuppressWarnings("SameParameterValue")
class EchoTest {
    private final OkHttpClient client = new OkHttpClient.Builder()
            .followRedirects(false)
            .callTimeout(Duration.ofMillis(20000))
//                .protocols(Collections.singletonList(Protocol.H2_PRIOR_KNOWLEDGE))
            .retryOnConnectionFailure(false)
            .build();

    @Test
    void test404() throws Exception {
        WebServer server = new WebServer("localhost", 12345);
        server.start();

        final var response = sendRequest(server.getBoundAddress().getPort(),"/BAD_PATH");
        assertEquals(404,response.code());

        server.stop(Duration.ofSeconds(1));
    }

    @ParameterizedTest // TODO test empty string
    @ValueSource(strings = {" ", "Hello World!", """
            This is a multi line test
            \r\n with special chars \t \u1827
            """})
    void testEcho(String testString) throws Exception {
        WebServer server = new WebServer("localhost", WebServer.EPHEMERAL_PORT);
        server.getRoutes().post("/echo", request -> {
            final int contentSize = request.getRequestHeaders().getContentLength();
            System.out.println("contentSize = " + contentSize);
            final InputStream in = request.getRequestBody();
            byte[] contentBytes = new byte[contentSize];
            int bytesRead = in.read(contentBytes);
            assertEquals(contentSize, bytesRead);
            in.close();
            System.out.println("contentBytes = [" + new String(contentBytes)+"]");
            // create response
            final WebHeaders responseHeaders = new WebHeaders();
            responseHeaders.setContentLength(contentSize);
            responseHeaders.setContentType(request.getRequestHeaders().getContentType());
            try(final OutputStream out = request.startResponse(StatusCode.OK_200,responseHeaders)) {
                out.write(contentBytes);
            }
        });
        server.start();

        final Request request = new Request.Builder()
                .url("http://localhost:" + server.getBoundAddress().getPort() + "/echo")
                .post(RequestBody.create(testString, MediaType.get("text/plain")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

            final String responseBody = response.body().string();
            assertEquals(testString, responseBody);
        }

        server.stop(Duration.ofSeconds(1));
    }

    private Response sendRequest(int port, String path) throws Exception {
        final var request = new Request.Builder()
                .url("http://localhost:" + port + path)
                .get()
                .build();

        final var response = client.newCall(request).execute();
        System.out.println(response.body().string());
        return response;
    }

    public static void main(String[] args) throws IOException {
        WebServer server = new WebServer("localhost", WebServer.EPHEMERAL_PORT);
        server.getRoutes().post("/echo", request -> {
            final int contentSize = request.getRequestHeaders().getContentLength();
            System.out.println("contentSize = " + contentSize);
            final InputStream in = request.getRequestBody();
            byte[] contentBytes = new byte[contentSize];
            int bytesRead = in.read(contentBytes);
            assertEquals(contentSize, bytesRead);
            in.close();
            System.out.println("contentBytes = [" + new String(contentBytes)+"]");
            // create response
            final WebHeaders responseHeaders = new WebHeaders();
            responseHeaders.setContentLength(contentSize);
            responseHeaders.setContentType(request.getRequestHeaders().getContentType());
            try(final OutputStream out = request.startResponse(StatusCode.OK_200,responseHeaders)) {
                out.write(contentBytes);
            }
        });
        server.start();
        System.out.println("server.getBoundAddress().getPort() = " + server.getBoundAddress().getPort());
    }

}

/*

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "Hello World!", """
            This is a multi line test
            \r\n with special chars \t \u1827
            """})
    void testEcho(String testString) throws Exception {
        WebServer server = new WebServer("localhost", WebServer.EPHEMERAL_PORT);
        server.getRoutes().post("/echo", request -> {
            final int contentSize = request.getRequestHeaders().getContentLength();
            System.out.println("contentSize = " + contentSize);
            final InputStream in = request.getRequestBody();
            byte[] contentBytes = new byte[contentSize];
            int bytesRead = in.read(contentBytes);
            assertEquals(contentSize, bytesRead);
            in.close();
            System.out.println("contentBytes = [" + new String(contentBytes)+"]");
            // create response
            final WebHeaders responseHeaders = new WebHeaders();
            responseHeaders.setContentLength(contentSize);
            responseHeaders.setContentType(request.getRequestHeaders().getContentType());
            try(final OutputStream out = request.startResponse(StatusCode.OK_200,responseHeaders)) {
                out.write(contentBytes);
            }
        });
        server.start();
//
//        try(final var helloResponse = sendRequest(server.getBoundAddress().getPort(), "/echo", testString)) {
//            System.out.println("helloResponse = " + helloResponse);
//            System.out.println("helloResponse.headers() = " + helloResponse.headers().toString().replace("\n","\n            "));
//            System.out.println("helloResponse.body() = " + helloResponse.body());
//            System.out.println("helloResponse.body().contentLength = " + helloResponse.body().contentLength());
//            System.out.println("helloResponse.body().contentType = " + helloResponse.body().contentType());
//            System.out.println("helloResponse.body().bytes = " + helloResponse.body().bytes());
//            assertEquals(testString, helloResponse.body().string());
//        }

        final Request request = new Request.Builder()
                .url("http://localhost:" + server.getBoundAddress().getPort() + "/echo")
                .post(RequestBody.create(testString, MediaType.get("text/plain")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);

            System.out.println(response.body().string());
            assertEquals(testString, response.body().string());
        }


        server.stop(Duration.ofSeconds(1));
    }

 */