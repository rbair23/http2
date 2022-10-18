package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;
import com.hedera.hashgraph.web.impl.util.OutputBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static com.hedera.hashgraph.web.impl.http2.frames.Frame.FRAME_HEADER_SIZE;
import static com.hedera.hashgraph.web.impl.http2.frames.PingFrame.PAYLOAD_LENGTH;
import static org.junit.jupiter.api.Assertions.*;

class PingFrameTest extends FrameTestBase {

    @Test
    void defaultPingFrameConstructor() {
        final var frame = new PingFrame();
        assertEquals(PAYLOAD_LENGTH, frame.getPayloadLength());
        assertEquals(0, frame.getStreamId());
        assertFalse(frame.isAck());
        assertEquals(0, frame.getData());
        assertEquals(FrameType.PING, frame.getType());
    }

    @Test
    void twoArgPingFrameConstructor() {
        final var data = randomLong();
        final var frame = new PingFrame(true, data);
        assertEquals(PAYLOAD_LENGTH, frame.getPayloadLength());
        assertEquals(0, frame.getStreamId());
        assertTrue(frame.isAck());
        assertEquals(data, frame.getData());
        assertEquals(FrameType.PING, frame.getType());
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void parseValidBytesNonAck(boolean ack) {
        final var data = randomLong();
        outputBuffer.write24BitInteger(PAYLOAD_LENGTH);
        outputBuffer.writeByte(FrameType.PING.ordinal());
        outputBuffer.writeByte(ack ? 1 : 0);
        outputBuffer.write32BitInteger(0);
        outputBuffer.write64BitLong(data);
        fillInputBuffer();

        final var frame = new PingFrame();
        frame.parse2(inputBuffer);
        assertEquals(PAYLOAD_LENGTH, frame.getPayloadLength());
        assertEquals(0, frame.getStreamId());
        assertEquals(ack, frame.isAck());
        assertEquals(data, frame.getData());
        assertEquals(FrameType.PING, frame.getType());
    }

    @Test
    // Negative test
    void parseInvalidBytes_WrongPayloadLength() {
        final var badLength = 1234;
        outputBuffer.write24BitInteger(badLength);
        outputBuffer.writeByte(FrameType.PING.ordinal());
        outputBuffer.writeByte(0);
        outputBuffer.write32BitInteger(0);
        outputBuffer.write64BitLong(randomLong());
        fillInputBuffer();

        final var frame = new PingFrame();
        try {
            frame.parse2(inputBuffer);
            fail();
        } catch(Http2Exception e) {
            assertEquals(Http2ErrorCode.FRAME_SIZE_ERROR, e.getCode());
            assertEquals(0, e.getStreamId());
        }
    }

    @Test
    // Negative test
    void parseInvalidBytes_StreamIdNotZero() {
        final var badStreamId = 74;
        outputBuffer.write24BitInteger(PAYLOAD_LENGTH);
        outputBuffer.writeByte(FrameType.PING.ordinal());
        outputBuffer.writeByte(0);
        outputBuffer.write32BitInteger(badStreamId);
        outputBuffer.write64BitLong(randomLong());
        fillInputBuffer();

        final var frame = new PingFrame();
        try {
            frame.parse2(inputBuffer);
            fail();
        } catch(Http2Exception e) {
            assertEquals(Http2ErrorCode.PROTOCOL_ERROR, e.getCode());
            assertEquals(badStreamId, e.getStreamId());
        }
    }

    @Test
    void parseValidBytes_UnexpectedFlagsIgnored() {
        final var data = randomLong();
        outputBuffer.write24BitInteger(PAYLOAD_LENGTH);
        outputBuffer.writeByte(FrameType.PING.ordinal());
        outputBuffer.writeByte(0b1111_1110);
        outputBuffer.write32BitInteger(0);
        outputBuffer.write64BitLong(data);
        fillInputBuffer();

        final var frame = new PingFrame();
        frame.parse2(inputBuffer);
        assertEquals(PAYLOAD_LENGTH, frame.getPayloadLength());
        assertEquals(0, frame.getStreamId());
        assertFalse(frame.isAck());
        assertEquals(data, frame.getData());
        assertEquals(FrameType.PING, frame.getType());
    }

    @Test
    void writeAck() {
        final var data = randomLong();
        final var ping = new PingFrame(false, data);
        ping.writeAck(outputBuffer);
        fillInputBuffer();

        final var frame = new PingFrame();
        frame.parse2(inputBuffer);
        assertEquals(PAYLOAD_LENGTH, frame.getPayloadLength());
        assertEquals(0, frame.getStreamId());
        assertTrue(frame.isAck());
        assertEquals(data, frame.getData());
        assertEquals(FrameType.PING, frame.getType());
    }

    @Test
    void write() {
        final var data = randomLong();
        final var ping = new PingFrame(false, data);
        ping.write(outputBuffer);
        fillInputBuffer();

        final var frame = new PingFrame();
        frame.parse2(inputBuffer);
        assertEquals(PAYLOAD_LENGTH, frame.getPayloadLength());
        assertEquals(0, frame.getStreamId());
        assertFalse(frame.isAck());
        assertEquals(data, frame.getData());
        assertEquals(FrameType.PING, frame.getType());
    }

    @ParameterizedTest
    @MethodSource("provideFuzzInputs")
    // Fuzz Test
    void fuzz(byte[] data, boolean shouldThrow) {
        outputBuffer.write(data, 0, data.length);
        fillInputBuffer();
        final var frame = new PingFrame();
        if (shouldThrow) {
            assertThrows(Http2Exception.class, () -> frame.parse2(inputBuffer));
        }
    }

    /**
     * Provides a host of random bad bytes
     *
     * @return Random bad bytes. They should ALL fail with Http2Exception when parsed
     */
    private static Stream<Arguments> provideFuzzInputs() {
        final var out = new OutputBuffer(FRAME_HEADER_SIZE + PAYLOAD_LENGTH);
        final var ping = new PingFrame(false, 0b0010_0101_0011_1100_1010_1011_1101_1111L);
        ping.write(out);
        final var goodBytes = out.getBuffer().array();
        final var rand = new Random(10293848673L);

        final var args = new ArrayList<Arguments>();
        for (int i = 0; i < 10_000; i++) { // 10_000 is some arbitrary large size
            for (int j = 0; j < FRAME_HEADER_SIZE; j++) {
                // Skip the "ordinal" byte, because we will never get to the parsing
                // code if the ordinal isn't right. We use an assert to verify that.
                if (j == 3) continue;
                args.add(Arguments.of(corrupt(rand, goodBytes, j), j != 4));
            }
        }

        return args.stream();
    }

    private static Object corrupt(Random rand, byte[] original, int position) {
        final var corrupted = Arrays.copyOf(original, original.length);
        final var originalValue = original[position];
        var corruptedValue = originalValue;
        while (corruptedValue == originalValue) {
            corruptedValue = (byte) rand.nextInt();
        }
        corrupted[position] = corruptedValue;
        return corrupted;
    }
}
