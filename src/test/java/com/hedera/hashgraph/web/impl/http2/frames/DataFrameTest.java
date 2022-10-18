package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.xml.crypto.Data;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static com.hedera.hashgraph.web.impl.http2.frames.Frame.FRAME_HEADER_SIZE;
import static com.hedera.hashgraph.web.impl.http2.frames.PingFrame.PAYLOAD_LENGTH;
import static org.junit.jupiter.api.Assertions.*;

class DataFrameTest extends FrameTestBase {

    @Test
    void defaultDataFrameConstructor() {
        final var frame = new DataFrame();
        assertEquals(0, frame.getPayloadLength());
        assertEquals(0, frame.getStreamId());
        assertFalse(frame.isEndStream());
        assertNotNull(frame.getData());
        assertEquals(1024, frame.getData().length);
        assertEquals(0, frame.getDataLength());
        assertEquals(FrameType.DATA, frame.getType());
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            true, 1, Hello World!
            false, 1, Hello World!
            false, 2, Hello World!
            """)
    void customDataFrameConstructor(boolean endStream, int streamId, String data) {
        final var frame = new DataFrame(endStream, streamId, data.getBytes(), data.length());
        assertEquals(data.length(), frame.getPayloadLength());
        assertEquals(streamId, frame.getStreamId());
        assertEquals(endStream, frame.isEndStream());
        assertNotNull(frame.getData());
        assertEquals(data.length(), frame.getData().length);
        assertEquals(data.length(), frame.getDataLength());
        assertEquals(FrameType.DATA, frame.getType());
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            true, -1, Hello World!
            true, 0, Hello World!
            true, 1, null
            """, nullValues = "null")
    // Negative test
    void customDataFrameConstructorBad(boolean endStream, int streamId, String data) {
        assertThrows(Throwable.class, () -> new DataFrame(endStream, streamId, data.getBytes(), data.length()));
    }

    @Test
    void lengthIsTooLargeThrows() {
        assertThrows(Throwable.class, () -> new DataFrame(false, 1, randomBytes(10), 11));
    }

    @Test
    void setEndStream() {
        final var frame = new DataFrame();
        frame.setEndStream(true);
        assertTrue(frame.isEndStream());
        frame.setEndStream(false);
        assertFalse(frame.isEndStream());
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            false,   0,    0
            false,   0,  200
            false,   0, 5123
            false, 200,    0
            false, 200,  200
            false, 200, 5123
            true,    0,    0
            true,    0,  200
            true,    0, 5123
            true,  200,    0
            true,  200,  200
            true,  200, 5123
            """)
    void parseValidBytes(boolean endStream, int paddingLength, int dataLength) {
        writeFrameByHand(outputBuffer, 1, endStream, paddingLength, dataLength);
        fillInputBuffer();
        final var frame = new DataFrame();
        frame.parse2(inputBuffer);
        assertEquals(dataLength, frame.getPayloadLength());
        assertEquals(1, frame.getStreamId());
        assertEquals(endStream, frame.isEndStream());
        assertNotNull(frame.getData());
        assertEquals(0, frame.getData().length % 1024); // 1024 or a multiple of it
        assertEquals(dataLength, frame.getDataLength());
        assertEquals(FrameType.DATA, frame.getType());
    }

    // TODO Should move to FrameTest
    @ParameterizedTest
    @ValueSource(ints = { -2, -1 })
    void parseValidBytes_HighBitOnStreamIdIsIgnored(int streamId) {
        writeFrameByHand(outputBuffer, streamId, false, 0, 0);
        fillInputBuffer();
        final var frame = new DataFrame();
        frame.parse2(inputBuffer);

        assertEquals(streamId & 0x7FFFFFFF, frame.getStreamId());
    }

    @Test
    void parseInvalidBytes_StreamId() {
        writeFrameByHand(outputBuffer, 0, false, 0, 0);
        fillInputBuffer();
        final var frame = new DataFrame();
        assertThrows(Http2Exception.class, () -> frame.parse2(inputBuffer));
    }

    private static void writeFrameByHand(OutputBuffer outputBuffer, int streamId, boolean endStream, int paddingLength, int dataLength) {
        final var data = randomBytes(dataLength);
        final var padding = randomBytes(paddingLength);
        var flags = endStream ? 1 : 0;
        if (paddingLength > 0) {
            flags |= 0b0000_1000;
        }

        outputBuffer.write24BitInteger(paddingLength > 0 ? paddingLength + dataLength + 1 : dataLength);
        outputBuffer.writeByte(FrameType.DATA.ordinal());
        outputBuffer.writeByte(flags);
        outputBuffer.write32BitInteger(streamId);

        if (paddingLength > 0) {
            outputBuffer.writeByte(paddingLength);
        }
        outputBuffer.write(data, 0, dataLength);
        if (paddingLength > 0) {
            outputBuffer.write(padding, 0, paddingLength);
        }
    }

    // Tests to write:
    //                  bad frames (fuzz testing)
    //    - write:      frames in various configurations all produce something good
}
