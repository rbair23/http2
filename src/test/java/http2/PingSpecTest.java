package http2;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.frames.*;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;

import java.io.IOException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 */
@DisplayName("Section 3.4. HTTP/2 Connection Preface")
class PingSpecTest extends SpecTest {

    // Section 6.7
    // Make sure we test that if the client pings, the client responds
    // Make sure that if the server pings, and the client responds, that the server does not respond again.
    // (Except the server, today, never pings. Maybe it should? Maybe not...)
}
