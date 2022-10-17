package com.hedera.hashgraph.web.impl.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class InputBufferTest {

    private final Random rand = new Random(647389);

    @ParameterizedTest
    @ValueSource(ints = { Integer.MIN_VALUE, -43, -42, -1 })
    void createBufferWithNegativeSizeThrows(int negativeSize) {
        assertThrows(IllegalArgumentException.class, () -> new InputBuffer(negativeSize));
    }

    @Test
    void createBufferWithZeroSizeThrows() {
        assertThrows(IllegalArgumentException.class, () -> new InputBuffer(0));
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 9, 20 })
    void createBufferWithPositiveSize(int positiveSize) throws IOException {
        // Can I create it?
        final var buf = new InputBuffer(positiveSize);

        // Can I put positiveSize bytes into it?
        var channel = new MockReadableChannel(randomBytes(positiveSize));
        buf.addData(channel);
        assertTrue(buf.available(positiveSize));

        // Does it fail to add just one more byte? The returned value will say "Yes, there are
        // bytes still to be read from the channel, I couldn't get them all"
        channel = new MockReadableChannel(randomBytes(1));
        assertTrue(buf.addData(channel));
    }

    @Test
    void addDataFromClosedChannelThrows() {
        final var buf = new InputBuffer(16);
        final var channel = new MockReadableChannel(randomBytes(16));
        channel.close();
        assertThrows(ClosedChannelException.class, () -> buf.addData(channel));
    }

    @Test
    void addDataFromChannelWithNoAvailableBytes() throws IOException {
        // "False" means, "There are still bytes on the channel I haven't read". In this case,
        // there are no bytes on the channel to read (I had room in my buffer to fill, but
        // the channel didn't have any bytes to give me, so I read them all).
        final var buf = new InputBuffer(16);
        final var channel = new MockReadableChannel(new byte[0]);
        assertFalse(buf.addData(channel));

        // And just to be crazy, we should get the same result every time as long as there are no
        // new bytes to read
        for (int i = 0; i < 5; i++) {
            assertFalse(buf.addData(channel));
        }
    }

    @Test
    void addDataFromChannelWithJustEnoughAvailableByte() throws IOException {
        final var buf = new InputBuffer(16);
        final var channel = new MockReadableChannel(randomBytes(16));
        assertTrue(buf.addData(channel)); // True, because *maybe* there are more bytes available
    }

    @Test
    void addDataFromChannelWithMoreBytesThanCanFillMyBuffer() throws IOException {
        final var buf = new InputBuffer(16);
        final var channelBytes = randomBytes(24);
        final var channel = new MockReadableChannel(channelBytes);

        // At first, there is more data available on the channel than I can read into my buffer,
        // so I must report "true" -- I left data behind.
        assertTrue(buf.addData(channel));

        // Now let's read off 10 bytes worth of data. That will leave me with a buffer that is
        // completely full, but the first 10 bytes are no longer useful.
        final var bytes = new byte[24];
        buf.readBytes(bytes, 0, 10);
        for (int i = 0; i < 10; i++) {
            assertEquals(channelBytes[i], bytes[i]);
        }

        // This time when I go to read more data from the channel, the buf will move unread bytes
        // to the start of the buffer to make room for more data. And it will read as many available
        // bytes from the channel as it can. In this case, that means it will read the remaining 8
        // bytes, and report "false" -- it did not leave any data on the buffer
        assertFalse(buf.addData(channel));

        // Now let's read off the remaining 14 bytes and check them
        buf.readBytes(bytes, 0, 14);
        for (int i = 0; i < 14; i++) {
            assertEquals(channelBytes[i + 10], bytes[i]);
        }

        // Now if I try to read one more byte, the buffer cannot supply it
        assertFalse(buf.available(1));
    }

    @Test
    void addDataFromChannelWithMoreBytesThanCanFillMyBufferMultipleTimes() throws IOException {
        final var buf = new InputBuffer(16);
        // Some random number of bytes that isn't easily divisible by 16
        final var channelBytes = randomBytes(8239);
        final var channel = new MockReadableChannel(channelBytes);

        // Keep track of the number of bytes I've read from the buf during each iteration.
        int numBytesRead = 0;
        final byte[] bytes = new byte[8239];

        // Keep iterating until we've finally read all the data
        while (numBytesRead < channelBytes.length) {
            // Pick a random number of bytes to be available in the channel
            channel.setMaxBytesAvailable(rand.nextInt(0, 20));
            // Load the buffer
            buf.addData(channel);
            // Read out some random number of bytes
            final var length = numBytesRead + buf.remaining() == 8239
                    ? buf.remaining()
                    : rand.nextInt(0, buf.remaining());
            buf.readBytes(bytes, 0, length);
            for (int i = 0; i < length; i++) {
                assertEquals(channelBytes[numBytesRead + i], bytes[i]);
            }
            numBytesRead += length;
        }
    }

    @Test
    void resetTheBuffer() throws IOException {
        final var buf = new InputBuffer(16);
        final var channelBytes = sequentialBytes(8);

        // Fill up the buffer with all 8 bytes of channel data, and then read back 4 of those
        // bytes from the buffer, leaving 4 behind.
        final var bytes = new byte[8];
        buf.addData(new MockReadableChannel(channelBytes));
        buf.readBytes(bytes, 0, 4);
        for (int i = 0; i < 4; i++) {
            assertEquals(channelBytes[i], bytes[i]);
        }

        // Now reset the buffer. If this DOESN'T work, then the buffer will still have 4 bytes from
        // the previous data in it, which will mess up the next test. If it DOES work, then the
        // buffer is back in a "clean" state and ready to go.
        buf.reset();

        // Read all 8 bytes of channel data. If it worked, the byte array will have 8 bytes, from
        // 1-8. If it didn't work, it would have "4, 5, 6, 7, 0, 1, 2, 3".
        buf.addData(new MockReadableChannel(channelBytes));
        buf.readBytes(bytes, 0, 8);
        for (int i = 0; i < 8; i++) {
            assertEquals(channelBytes[i], bytes[i]);
        }
    }

    @Test
    void markIsResetWhen() {
        // TODO Under what conditions is the "mark" automatically reset?
    }

    @Test
    void resetToMarkDoesNothingIfMarkWasNotSet() throws IOException {
        final var buf = new InputBuffer(16);
        final var channel = new MockReadableChannel(sequentialBytes(16));
        buf.addData(channel);
        buf.skip(8);
        buf.resetToMark();
        assertEquals(8, buf.readByte());
    }

    @Test
    // HAPPY path
    void markReadReset() throws IOException {
        final var buf = new InputBuffer(16);
        final var channel = new MockReadableChannel(sequentialBytes(16));

        // [ 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, ... ]
        //   ^
        buf.addData(channel);

        // [ 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, ... ]
        //               ^
        buf.skip(4);
        buf.mark();
        // [ 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, ... ]
        //               -        ^
        buf.skip(3);
        assertEquals(3, buf.getNumMarkedBytes());
        assertEquals(7, buf.readByte());
        assertEquals(8, buf.readByte());
        // [ 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, ... ]
        //               -              ^
        // [ 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, ... ]
        //               ^
        int delta = buf.resetToMark();
        assertEquals(5, delta);
        assertEquals(4, buf.readByte());
    }

    @Test
    void availableOfZeroBytesIsAlwaysTrue() {
        final var buf = new InputBuffer(8);
        assertTrue(buf.available(0));
    }

    @Test
    void availableOfNegativeBytesIsAlwaysTrue() {
        final var buf = new InputBuffer(8);
        assertTrue(buf.available(-1));
        assertTrue(buf.available(Integer.MIN_VALUE));
    }

    @Test
    void noBytesAvailableOnFreshBuffer() {
        final var buf = new InputBuffer(8);
        assertFalse(buf.available(1));
    }

    @Test
    void bytesAvailableChangesAsBytesInBufferAreRead() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(randomBytes(8));
        buf.addData(channel);

        for (int i = 0; i < 8; i++) {
            final var bytesRemaining = 8 - i;
            for (int j = 0; j < bytesRemaining; j++) {
                assertTrue(buf.available(j));
            }

            for (int j = bytesRemaining + 1; j < 8; j++) {
                assertFalse(buf.available(j));
            }

            buf.skip(1);
        }
    }

    @Test
    void bytesAvailableResetsAsNewBytesAreAddedFromChannel() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(randomBytes(12));
        buf.addData(channel);
        buf.skip(7);
        assertTrue(buf.available(1));
        assertFalse(buf.available(2));

        buf.addData(channel); // Adds 4 more bytes
        assertTrue(buf.available(5));
        assertFalse(buf.available(6));
    }

    @Test
    void exhaustedBufferHasNoBytesAvailable() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(randomBytes(8));
        buf.addData(channel);
        buf.skip(8);
        assertFalse(buf.available(1));
    }

    //----------------------------------------------------------------------------------------
    // peekByte()

    @Test
    void peekByteFailsWhenNoDataIsAvailable() {
        final var buf = new InputBuffer(8);
        assertThrows(BufferUnderflowException.class, buf::peekByte);
    }

    @Test
    void peekByteFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(sequentialBytes(8));
        buf.addData(channel);
        buf.skip(8);
        assertThrows(BufferUnderflowException.class, buf::peekByte);
    }

    @Test
    void peekByteGetsNextByteAndDoesNotMoveReadPosition() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(sequentialBytes(8));
        buf.addData(channel);

        for (int i = 0; i < 8; i++) {
            assertEquals(i, buf.peekByte());
            assertEquals(i, buf.peekByte());
            assertTrue(buf.available(8 - i));
            buf.skip(1);
        }
    }

    //----------------------------------------------------------------------------------------
    // peekByte(int numBytesToLookPast)

    @Test
    void peekByteWithLookPastFailsWhenNoDataIsAvailable() {
        final var buf = new InputBuffer(8);
        assertThrows(BufferUnderflowException.class, () -> buf.peekByte(1));
    }

    @Test
    void peekByteWithLookPastFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(sequentialBytes(8));
        buf.addData(channel);
        buf.skip(7);
        assertThrows(BufferUnderflowException.class, () -> buf.peekByte(2));
    }

    @Test
    void peekByteWithLookPastFailsWhenNotEnoughDataIsAvailable() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(sequentialBytes(4));
        buf.addData(channel);
        assertThrows(BufferUnderflowException.class, () -> buf.peekByte(5));
    }

    @Test
    void peekByteWithLookPastGetsNextByteAndDoesNotMoveReadPosition() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(sequentialBytes(8));
        buf.addData(channel);

        for (int i = 0; i < 4; i++) {
            assertEquals(i + 4, buf.peekByte(4));
            assertEquals(i+ 4, buf.peekByte(4));
            assertTrue(buf.available(8 - i));
            buf.skip(1);
        }
    }

    //----------------------------------------------------------------------------------------
    // readBytes(byte[] dst, int dstOffset, int numBytes)

    @Test
    void readBytesFailsWhenNoDataIsAvailable() {
        final var buf = new InputBuffer(8);
        final var bytes = new byte[8];
        assertThrows(BufferUnderflowException.class, () -> buf.readBytes(bytes, 0, 8));
    }

//    @Test
//    void readBytesFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
//        final var buf = new InputBuffer(8);
//        final var channel = new MockReadableChannel(sequentialBytes(8));
//        buf.addData(channel);
//        buf.skip(8);
//        assertThrows(BufferUnderflowException.class, buf::peekByte);
//    }

//    @Test
//    void readBytesGetsNextByteAndDoesNotMoveReadPosition() throws IOException {
//        final var buf = new InputBuffer(8);
//        final var channel = new MockReadableChannel(sequentialBytes(8));
//        buf.addData(channel);
//
//        for (int i = 0; i < 8; i++) {
//            assertEquals(i, buf.peekByte());
//            assertEquals(i, buf.peekByte());
//            assertTrue(buf.available(8 - i));
//            buf.skip(1);
//        }
//    }

    //----------------------------------------------------------------------------------------
    // Test Utilities

    /**
     * Generates some random bytes
     *
     * @param length The number of bytes to generate.
     * @return Some random bytes.
     */
    private byte[] randomBytes(int length) {
        final byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) rand.nextInt();
        }
        return data;
    }

    /**
     * Generates a byte array where each index is a sequential byte number. Index 0 has the
     * value of "0", 1 has "1", and so on.
     *
     * @param length The number of bytes to generate. Must be less than 256.
     * @return An array of sequential values.
     */
    private byte[] sequentialBytes(int length) {
        assert length < 256;
        final byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) i;
        }
        return data;
    }

    /**
     * A {@link ReadableByteChannel} that we can use for testing purposes
     */
    private static final class MockReadableChannel implements ReadableByteChannel {
        private boolean closed = false;
        private final byte[] dataInChannel;
        private int position = 0;
        private int maxBytesAvailable = Integer.MAX_VALUE;

        MockReadableChannel(byte[] dataInChannel) {
            this.dataInChannel = dataInChannel;
        }

        void setMaxBytesAvailable(int max) {
            assert max >= 0;
            this.maxBytesAvailable = max;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            if (position >= dataInChannel.length) {
                return -1;
            }

            final int remaining = dst.remaining();
            final int available = Math.min(dataInChannel.length - position, maxBytesAvailable);
            final int length = Math.min(remaining, available);
            dst.put(dataInChannel, position, length);
            position += length;
            return length;
        }

        @Override
        public boolean isOpen() {
            return !closed;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
