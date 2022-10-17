package com.hedera.hashgraph.web.grpc;

import com.hedera.hashgraph.web.StatusCode;
import com.hedera.hashgraph.web.WebRequest;
import com.hedera.hashgraph.web.WebRoutes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.*;

public class GrpcRouter {
    private final String pathPrefix;
    private final WebRoutes webRoutes;
    private final Map<String, GrpcService> services = new HashMap<>();

    public GrpcRouter(final String path, final WebRoutes webRoutes) {
        this.pathPrefix = normalizePath(path);
        this.webRoutes = Objects.requireNonNull(webRoutes);
    }

    public GrpcRouter service(GrpcService.Builder serviceBuilder) {
        Objects.requireNonNull(serviceBuilder);
        return service(serviceBuilder.build());
    }

    public GrpcRouter service(GrpcService service) {
        Objects.requireNonNull(service);

        final var serviceName = service.name();
        services.put(serviceName, service);
        webRoutes.post(serviceName, this::httpHandler);

        return this;
    }

    private void httpHandler(final WebRequest req) throws IOException {
        final var headers = req.getRequestHeaders();

        final var method = req.getMethod();
        if (!"POST".equals(method)) {
            req.respond(StatusCode.METHOD_NOT_ALLOWED_405);
            return;
        }

//        final var scheme = httpExchange.getRequestURI().getScheme();
//        if (!("http".equals(scheme) || "https".equals(scheme))) {
//            // HTTP StatusCode.BAD_REQUEST Bad Request
//            req.respond(StatusCode.BAD_REQUEST);
//            return;
//        }

        final var path = req.getPath();
        GrpcMethod grpcMethod;
        if (path == null) {
            // HTTP StatusCode.NOT_FOUND
            req.respond(StatusCode.NOT_FOUND_404);
            return;
        } else if (!path.startsWith(pathPrefix + "/")) {
            req.respond(StatusCode.NOT_FOUND_404);
            return;
        } else {
            // Parse out the service name
            final var pathAfterPrefix = path.substring(pathPrefix.length() + 1); // include the trailing slash
            final var serviceNameInPath = pathAfterPrefix.substring(0, pathAfterPrefix.indexOf('/'));

            final var grpcService = services.get(serviceNameInPath);
            if (grpcService == null) {
                req.respond(StatusCode.NOT_FOUND_404);
                return;
            }

            // Parse off the method name
            final var methodName = pathAfterPrefix.substring(pathAfterPrefix.indexOf('/') + 1);
            grpcMethod = grpcService.method(methodName);
            if (grpcMethod == null) {
                req.respond(StatusCode.NOT_FOUND_404);
                return;
            }
        }

        // Finally, something interesting. This gives me the name of the gRPC service to invoke!!
        final var serviceName = headers.get("Service-Name");
        // Not sure if I care much about this...

        final var authority = headers.get("Authority");
        // I'm ignoring this for now...?

        final var te = headers.get("TE");
        if (te != null && !te.contains("trailers")) {
            // SPEC: "te" "trailers" # Used to detect incompatible proxies
            // HTTP StatusCode.BAD_REQUEST Bad Request
            req.respond(StatusCode.BAD_REQUEST_400);
            return;
        }

        final var timeout = headers.getAsList("Timeout");
        Duration t = Duration.ofMinutes(5); // This is our maximum default, not infinite
        if (timeout != null) {
            if (timeout.size() != 3) {
                // HTTP StatusCode.BAD_REQUEST Bad Request
                req.respond(StatusCode.BAD_REQUEST_400);
                return;
            }

            if (!"grpc-timeout".equals(timeout.get(0))) {
                // HTTP StatusCode.BAD_REQUEST Bad Request
                req.respond(StatusCode.BAD_REQUEST_400);
                return;
            }

            final var timeoutValueString = timeout.get(1);
            if (timeoutValueString.length() > 8) {
                // SPEC: positive integer as ASCII string of at most 8 digits
                // HTTP StatusCode.BAD_REQUEST Bad Request
                req.respond(StatusCode.BAD_REQUEST_400);
                return;
            }

            int timeoutValue;
            try {
                timeoutValue = Integer.parseInt(timeoutValueString);
            } catch (NumberFormatException ex) {
                // SPEC: positive integer as ASCII string of at most 8 digits
                // HTTP StatusCode.BAD_REQUEST Bad Request
                req.respond(StatusCode.BAD_REQUEST_400);
                return;
            }

            final var timeoutUnit = timeout.get(2);
            t = switch (timeoutUnit) {
                case "H" -> Duration.ofHours(timeoutValue);
                case "M" -> Duration.ofMinutes(timeoutValue);
                case "S" -> Duration.ofSeconds(timeoutValue);
                case "m" -> Duration.ofMillis(timeoutValue);
                case "u" -> Duration.ofNanos(1000L * timeoutValue);
                case "n" -> Duration.ofNanos(timeoutValue);
                default -> null;
            };

            if (t == null) {
                // Bad timeout unit, so bad request.
                // HTTP StatusCode.BAD_REQUEST Bad Request
                req.respond(StatusCode.BAD_REQUEST_400);
                return;
            }
        }

        final var contentType = headers.get("Content-Type");
        if (contentType == null || !contentType.startsWith("application/grpc")) {
            // SPEC:
            // If Content-Type does not begin with "application/grpc", gRPC servers SHOULD respond with
            // HTTP status of StatusCode.UNSUPPORTED_MEDIA_TYPE (Unsupported Media Type). This will prevent other HTTP/2 clients from
            // interpreting a gRPC error response, which uses status 200 (OK), as successful.
            req.respond(StatusCode.UNSUPPORTED_MEDIA_TYPE_415);
            return;
        }

        final var messageEncoding = headers.getAsList("Message-Encoding");
        String contentCoding;
        if (messageEncoding != null) {
            if (messageEncoding.size() != 2) {
                req.respond(StatusCode.BAD_REQUEST_400);
                return;
            }

            if (!"grpc-encoding".equals(messageEncoding.get(0))) {
                req.respond(StatusCode.BAD_REQUEST_400);
                return;
            }

            contentCoding = messageEncoding.get(1);
            if (!("identity".equals(contentCoding) || "gzip".equals(contentCoding) || "deflate".equals(contentCoding) || "snappy".equals(contentCoding))) {
                req.respond(StatusCode.BAD_REQUEST_400);
                return;
            }
        }

        final var messageAcceptEncoding = headers.getAsList("Message-Accept-Encoding");
        final List<String> acceptableEncodings;
        if (messageAcceptEncoding != null) {
            if (messageAcceptEncoding.size() < 2) {
                req.respond(StatusCode.BAD_REQUEST_400);
                return;
            }

            if (!"grpc-accept-encoding".equals(messageAcceptEncoding.get(0))) {
                req.respond(StatusCode.BAD_REQUEST_400);
                return;
            }

            acceptableEncodings = new ArrayList<>(messageAcceptEncoding.subList(1, messageAcceptEncoding.size()));
            if (!(acceptableEncodings.contains("identity") || acceptableEncodings.contains("gzip") || acceptableEncodings.contains("deflate") || acceptableEncodings.contains("snappy"))) {
                req.respond(StatusCode.UNSUPPORTED_MEDIA_TYPE_415);
                return;
            }
        }

        // TODO Should really enforce content type of application/grpc+proto because this is only a proto grpc server right now
        // Great! I have a method, so I can call it.
        try {
            grpcMethod.handler().handle(new GrpcRequest() {
                @Override
                public InputStream protoRequestStream() throws IOException {
                    return req.getRequestBody();
                }

                @Override
                public OutputStream startResponse(GrpcStatus status) throws IOException {
                    // TODO gotta do some more work on this...
//                    return req.startResponse(StatusCode.OK_200, WebRequest.EMPTY_HEADERS);
                    return null;
                }
            });
        } catch (GrpcException e) {
            // TODO return a proper response
        }
    }

    // Doesn't try to deal with whitespace in the path. A more rigorous implementation would.
    private static String normalizePath(String path) {
        Objects.requireNonNull(path);
        if ("/".equals(path)) {
            return path;
        } else if (path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }

        return path;
    }
}
