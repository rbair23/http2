package http2.spec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;

@DisplayName("Section 6.7 PING")
class DataFrameSpecTest extends SpecTest {

    @BeforeEach
    void setUp() throws IOException {
        super.setUp();
//        client.initializeConnection();
    }

    // SPEC 6.1
    //
    // TESTS:
    //     - Send headers then data with last one flag (good)
    //     - Send headers then multiple data with last one with flag (good)
    //     - Send headers then data without last one flag (never responds until timeout)
    //     - Send headers then data with multiple data then last one flag then more data (with and w/out flag, bad)
    //     - Send headers then data with last one flag then another data (with and without flag, bad)
    //     - Data frame with 0 stream id
    //     -
}
