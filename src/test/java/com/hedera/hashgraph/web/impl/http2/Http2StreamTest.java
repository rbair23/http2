package com.hedera.hashgraph.web.impl.http2;

import com.hedera.hashgraph.web.HttpVersion;
import com.hedera.hashgraph.web.StatusCode;
import com.hedera.hashgraph.web.WebRoutes;
import com.hedera.hashgraph.web.WebServerConfig;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.SameThreadExecutorService;
import com.hedera.hashgraph.web.impl.http2.frames.HeadersFrame;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;
import com.hedera.hashgraph.web.impl.util.InputBuffer;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import com.twitter.hpack.Decoder;
import com.twitter.hpack.Encoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class Http2StreamTest {
    // An empty routes
    private WebRoutes routes;
    // A manager setup with a "same thread" executor and the above routes
    private ContextReuseManager ctxManager;
    private StubConnection connection;

    @BeforeEach
    void setUp() {
        routes = new WebRoutes();
        ctxManager = new ContextReuseManager(
                new Dispatcher(routes, new SameThreadExecutorService()),
                new WebServerConfig.Builder().build());
        connection = new StubConnection();
    }

    @Test
    void constructor_contextReuseManagerCannotBeNull() {
        assertThrows(NullPointerException.class, () ->
                new Http2Stream(null));
    }

    @Test
    void init_connectionCannotBeNull() {
        final var ctx = ctxManager.checkoutHttp2RequestContext();
        assertThrows(NullPointerException.class, () -> ctx.init(null, 1));
    }

    @Test
    void handleHeaders_headerFrameCannotBeNull() {
        final var ctx = ctxManager.checkoutHttp2RequestContext();
        ctx.init(new StubConnection(), 1);
        assertThrows(NullPointerException.class, () -> ctx.handleHeadersFrame(null));
    }

    // Handler that doesn't ever officially respond
    // Handler that responds with error
    // Handler that responds with 200 but no body
    // Handler that responds with body
    // Handler that throws IOException
    // Handler that throws RuntimeException
    // Handler that throws Throwable

    @Test
    void handleHeaders_completeHeaderWithNoDataFramesInvokesCallback() throws IOException {
        // When this route is invoked, it will check that the fields on the request object
        // were properly setup
        final var path = "/handleHeaders_completeHeaderWithNoDataFramesInvokesCallback";
        final var handlerCalled = new AtomicBoolean(false);
        routes.get(path, request -> {
            handlerCalled.set(true);
            assertEquals("GET", request.getMethod());
            assertEquals(path, request.getPath());
            assertEquals("alpha", request.getRequestHeaders().get("a"));
            assertEquals("beta", request.getRequestHeaders().get("b"));
            final var requestHeaders = request.getRequestHeaders();
            assertEquals(2, requestHeaders.keySet().size());

            assertEquals(HttpVersion.HTTP_2, request.getVersion());
            assertNotNull(request.getRequestBody());
            assertSame(request.getRequestBody(), request.getRequestBody());
            assertEquals(0, request.getRequestBody().available());
            assertEquals(-1, request.getRequestBody().read());

            request.setResponseStatusCode(StatusCode.OK_200);
        });

        final var ctx = ctxManager.checkoutHttp2RequestContext();
        ctx.init(connection, 1);

        final var requestHeaders = new Http2Headers();
        requestHeaders.put("a", "alpha");
        requestHeaders.put("b", "beta");
        requestHeaders.putMethod("GET");
        requestHeaders.putPath(path);

        final var out = new ByteArrayOutputStream();
        connection.getHeaderCodec().encode(requestHeaders, out);

        final var bytes = out.toByteArray();
        final var headerFrame = new HeadersFrame(true, false, 1, bytes, bytes.length);

        // Because the header frame is complete AND has "endOfStream" set to true, calling this method
        // will cause the handler to be called, and the response header to be sent to the connection
        ctx.handleHeadersFrame(headerFrame);
        assertTrue(handlerCalled.get());

        // Try to read back the response Header and see if it has the right stuff
        // TODO Cannot do this easily because we have no way to pipe from an output buffer
        //      to an input buffer. InputBuffer only wants to get data from a channel,
        //      which seems bogus.
        final var inputBuffer = new InputBuffer(1024*16);
        final var bb = inputBuffer.getBuffer();
        bb.put(connection.onWireOutput);
        bb.reset();
//        final var responseHeaderFrame = HeadersFrame.parse(inputBuffer);
//        assertTrue(responseHeaderFrame.isCompleteHeader());
//        assertTrue(responseHeaderFrame.isEndStream());
//
//        // Blah. I need a wrapper around the Encoder / Decoder so that I can easily encode / decode
//        // web headers without all this FUSS.
//        final var responseHeaders = new Http2Headers();
//        connection.getHeaderCodec().decode(
//                responseHeaders,
//                new ByteArrayInputStream(responseHeaderFrame.getFieldBlockFragment()));
//
//        assertEquals(StatusCode.OK_200, responseHeaders.getStatus());
    }

    private static final class StubConnection implements Http2Connection {
        private ByteBuffer onWireOutput = ByteBuffer.allocate(1024 * 1024);
        private Http2HeaderCodec codec = new Http2HeaderCodec(
                new Encoder(1000),
                new Decoder(1000, 1000));

        @Override
        public void sendOutput(OutputBuffer buffer) { onWireOutput.put(buffer.getBuffer()); }

        @Override
        public OutputBuffer getOutputBuffer() {
            return null;
        }

        @Override
        public void close(int streamId) {
            // no-op
        }

        @Override
        public Http2HeaderCodec getHeaderCodec() {
            return codec;
        }
    }
}
