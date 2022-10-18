package testapps;

import com.hedera.hashgraph.web.WebHeaders;
import com.hedera.hashgraph.web.WebServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class Http2ServerMain {
    public static void main(String[] args) throws IOException {
        WebServer server = new WebServer("localhost", 54321);
        server.getRoutes().get("/hello", request -> {
            // create response
            byte[] hello = "Hello back to you!".getBytes(StandardCharsets.US_ASCII);
//            request.getResponse()
//                    .body(hello)
//                    .contentType("text/plain")
//                    .header("C")
//            final WebHeaders responseHeaders = new WebHeaders();
//            responseHeaders.setContentLength(hello.length);
//            responseHeaders.setContentType("text/plain");
//            try(final OutputStream out = request.startResponse(StatusCode.OK_200,responseHeaders)) {
//                out.write(hello);
//            }
        });
        server.start();
        System.out.println("server.getBoundAddress().getPort() = " + server.getBoundAddress().getPort());
    }
}
