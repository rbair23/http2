package com.hedera.hashgraph.web.impl;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

/**
 * A simple server using built-in Java HTTP server for performance comparison testing
 */
public class JavaHttpServer {
    public static void main(String[] args) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(54321), 0);
        final var context = httpServer.createContext("/hello");
        context.setHandler(exchange -> {
            String response = "Hi there!";
            exchange.sendResponseHeaders(200, response.getBytes().length);//response code and length
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes());
            os.close();
        });
        httpServer.start();
    }
}
