package http2.spec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;

@DisplayName("Section 6.2 HEADERS")
public class HeadersSpecTest extends SpecTest {

    @BeforeEach
    void setUp() throws IOException {
        super.setUp();
        client.initializeConnection();
    }

    // TODO Devise tests that end all kinds of good headers, and bad ones.
    //      Only include tests of Data and Continuation frames where the
    //      section 6.2 requires it. Do send headers with actual header values,
    //      and empty headers.
}
