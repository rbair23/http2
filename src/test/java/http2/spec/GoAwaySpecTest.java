package http2.spec;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.frames.FrameType;
import com.hedera.hashgraph.web.impl.http2.frames.GoAwayFrame;
import com.hedera.hashgraph.web.impl.http2.frames.PingFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Specification tests for Section 6.8 GOAWAY.
 */
@DisplayName("Section 6.8 GOAWAY")
class GoAwaySpecTest extends SpecTest {

    @BeforeEach
    void setUp() throws IOException {
        super.setUp();
//        client.initializeConnection();
    }

    // TESTS:
    //     - Open some headers successfully (can be self-closed, that's fine), and then GOAWAY should give me the highest stream id
    //         - In whatever I send that causes the GOAWAY to happen, have buffered up some streams that haven't been sent yet
    //     - GOAWAY on a fresh connection returns 0 for the stream id
    //     - Send some DATA frames for a stream already opened before the GOAWAY. Should be OK.
    //     -
}
