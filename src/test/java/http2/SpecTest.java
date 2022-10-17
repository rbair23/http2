package http2;

import com.hedera.hashgraph.web.WebRoutes;
import com.hedera.hashgraph.web.WebServerConfig;
import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.impl.http2.Http2ConnectionImpl;
import com.hedera.hashgraph.web.impl.session.ContextReuseManager;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.ExecutorService;

abstract class SpecTest {
    protected static final String MALICIOUS = "Malicious";
    protected static final String PERFORMANCE = "Performance";
    protected static final String HAPPY_PATH = "Happy";
    protected static final String NEGATIVE = "Negative";

    protected Http2ConnectionImpl http2Connection;
    protected ExecutorService threadPool;
    protected MockByteChannel channel;

    @BeforeEach
    void setUp() {
        final var config = new WebServerConfig.Builder().build();
        final var routes = new WebRoutes();
        threadPool = new MockExecutorService();
        final var dispatcher = new Dispatcher(routes, threadPool);
        final var contextReuseManager = new ContextReuseManager(dispatcher, config);
        http2Connection = new Http2ConnectionImpl(contextReuseManager, config);
        channel = new MockByteChannel();
        http2Connection.reset(channel, null);
    }

}
