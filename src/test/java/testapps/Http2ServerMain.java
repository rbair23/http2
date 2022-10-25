package testapps;

import com.hedera.hashgraph.web.WebResponse;
import com.hedera.hashgraph.web.WebServer;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class Http2ServerMain {
    public static void main(String[] args) throws IOException {
        WebServer server = new WebServer("localhost", 54321);
        server.getRoutes().get("/hello", (request, response) -> {
            // create response
            response.respond(WebResponse.CONTENT_TYPE_PLAIN_TEXT, "Hello back to you!");
        });
        server.getRoutes().post("/echo", (request, response) -> {
            final int contentSize = request.getRequestHeaders().getContentLength();
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
        System.out.println("Server Started At http:/" + server.getBoundAddress()+"/hello");
    }
}
