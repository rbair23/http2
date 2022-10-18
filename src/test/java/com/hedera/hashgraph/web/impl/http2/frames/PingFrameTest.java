package com.hedera.hashgraph.web.impl.http2.frames;

import com.hedera.hashgraph.web.impl.http2.Http2ErrorCode;
import com.hedera.hashgraph.web.impl.http2.Http2Exception;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
}
