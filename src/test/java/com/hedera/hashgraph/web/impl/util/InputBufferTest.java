package com.hedera.hashgraph.web.impl.util;

import com.hedera.hashgraph.web.HttpVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Arrays;
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

    //----------------------------------------------------------------------------------------
    // init(InputBuffer other)

    @Test
    void initBufferWithNullThrows() {
        final var buf = new InputBuffer(16);
        //noinspection ConstantConditions
        assertThrows(NullPointerException.class, () -> buf.init(null));
    }

    @Test
    void initBufferWithEmptyBuffer() throws IOException {
        final var buf = new InputBuffer(16);
        final var other = new InputBuffer(16);
        buf.init(other);

        // Both buffers were empty
        assertFalse(buf.available(1));

        // Now try with the original buffer having data. It should be wiped out.
        buf.addData(new MockReadableChannel(randomBytes(8)));
        assertTrue(buf.available(8));
        buf.init(other);
        assertFalse(buf.available(1));
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            0, 0
            0, 8
            0, 16
            8, 0
            8, 8
            8, 16
            16, 0
            16, 8
            16, 16
            """)
    void initBufferWithOtherBuffer(int numBufBytes, int numOtherBytes) throws IOException {
        final var buf = new InputBuffer(16);
        buf.addData(new MockReadableChannel(randomBytes(numBufBytes)));
        // Let "other" have some bytes (but not fully filled)
        final var other = new InputBuffer(16);
        var otherBytes = randomBytes(numOtherBytes);
        other.addData(new MockReadableChannel(otherBytes));

        // Init the buf with "other". The buf now has the same bytes as "other" did.
        buf.init(other);
        assertArrayEquals(otherBytes, readBytes(buf, numOtherBytes));
        assertFalse(buf.available(1)); // No extra bytes
    }

    @Test
    void initLargerBufferWithSmaller() throws IOException {
        final var buf = new InputBuffer(32);
        buf.addData(new MockReadableChannel(randomBytes(8)));
        // Let "other" have some bytes (but not fully filled)
        final var other = new InputBuffer(16);
        var otherBytes = randomBytes(16);
        other.addData(new MockReadableChannel(otherBytes));

        // Init the buf with "other". The buf now has the same bytes as "other" did.
        buf.init(other);
        assertArrayEquals(otherBytes, readBytes(buf, 16));
        assertFalse(buf.available(1)); // No extra bytes
    }

    @Test
    void initSmallerBufferWithLarger() throws IOException {
        final var buf = new InputBuffer(16);
        buf.addData(new MockReadableChannel(randomBytes(8)));
        // Let "other" have some bytes (but not fully filled)
        final var other = new InputBuffer(32);
        var otherBytes = randomBytes(32);
        other.addData(new MockReadableChannel(otherBytes));

        assertThrows(ArrayIndexOutOfBoundsException.class, () -> buf.init(other));
    }

    //----------------------------------------------------------------------------------------
    // addData(ReadableByteChannel channel)

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

    //----------------------------------------------------------------------------------------
    // reset()

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

    //----------------------------------------------------------------------------------------
    // mark, resetToMark

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

    //----------------------------------------------------------------------------------------
    // available(int numBytes)

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

    @Test
    void readBytesFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(sequentialBytes(8));
        buf.addData(channel);
        buf.skip(7);

        final var bytes = new byte[8];
        assertThrows(BufferUnderflowException.class, () -> buf.readBytes(bytes, 0, 2));
    }

    @Test
    void readBytesGetsNextBytesAndMovesReadPosition() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(sequentialBytes(8));
        buf.addData(channel);

        // Note: I'm deliberately testing with passing a buffer bigger than length
        final var bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            buf.readBytes(bytes, 0, 2);
            assertEquals(i * 2, bytes[0]);
            assertEquals((i * 2) + 1, bytes[1]);
        }
    }

    @Test
    void readBytesWithLengthGreaterThanArrayLengthThrows() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(sequentialBytes(8));
        buf.addData(channel);

        // Note: I'm deliberately testing with passing a buffer bigger than length
        final var bytes = new byte[4];
        assertThrows(IndexOutOfBoundsException.class, () -> buf.readBytes(bytes, 0, 6));
    }

    //----------------------------------------------------------------------------------------
    // peekBytes(byte[] dst, int dstOffset, int numBytes)

    @Test
    void peekBytesFailsWhenNoDataIsAvailable() {
        final var buf = new InputBuffer(8);
        final var bytes = new byte[8];
        assertThrows(BufferUnderflowException.class, () -> buf.peekBytes(bytes, 0, 8));
    }

    @Test
    void peekBytesFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(sequentialBytes(8));
        buf.addData(channel);
        buf.skip(7);

        final var bytes = new byte[8];
        assertThrows(BufferUnderflowException.class, () -> buf.peekBytes(bytes, 0, 2));
    }

    @Test
    void peekBytesGetsNextBytesAndDoesNotMoveReadPosition() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(sequentialBytes(8));
        buf.addData(channel);

        final var bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            buf.peekBytes(bytes, 0, 4);
            for (int j = i; j < i + 4; j++) {
                assertEquals(j, bytes[j - i]);
            }

            buf.peekBytes(bytes, 0, 4);
            for (int j = i; j < i + 4; j++) {
                assertEquals(j, bytes[j - i]);
            }

            assertTrue(buf.available(8 - i));
            buf.skip(1);
        }
    }

    //----------------------------------------------------------------------------------------
    // readString(int numBytes, Charset charset)

    @Test
    void readStringFailsWhenNoDataIsAvailable() {
        final var buf = new InputBuffer(8);
        assertThrows(BufferUnderflowException.class, () -> buf.readString(4, StandardCharsets.US_ASCII));
    }

    @Test
    void readStringFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel("Hi There".getBytes(StandardCharsets.US_ASCII));
        buf.addData(channel);
        buf.skip(7);

        assertThrows(BufferUnderflowException.class, () -> buf.readString(2, StandardCharsets.US_ASCII));
    }

    @Test
    void readStringGetsNextBytesAndMovesReadPosition() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel("Hi There".getBytes(StandardCharsets.US_ASCII));
        buf.addData(channel);

        String s = buf.readString(1, StandardCharsets.US_ASCII);
        assertEquals("H", s);
        s = buf.readString(4, StandardCharsets.US_ASCII);
        assertEquals("i Th", s);
        s = buf.readString(3, StandardCharsets.US_ASCII);
        assertEquals("ere", s);
    }

    @ParameterizedTest
    @ValueSource(ints = { Integer.MIN_VALUE, -43, -12, -1, 0 })
    void readStringWithNegativeOrZeroLengthIsAlwaysEmpty(int numBytesToRead) throws IOException {
        final var buf = new InputBuffer(8);
        assertEquals("", buf.readString(numBytesToRead, StandardCharsets.US_ASCII));

        final var channel = new MockReadableChannel("Hi There".getBytes(StandardCharsets.US_ASCII));
        buf.addData(channel);
        assertEquals("", buf.readString(numBytesToRead, StandardCharsets.US_ASCII));

        buf.skip(8);
        assertEquals("", buf.readString(numBytesToRead, StandardCharsets.US_ASCII));
    }

    @Test
    void readStringWithNullCharsetThrows() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel("Hi There".getBytes(StandardCharsets.US_ASCII));
        buf.addData(channel);

        assertThrows(NullPointerException.class, () -> buf.readString(4, null));
    }

    @Test
    void readStringUsesCharset() throws IOException {
        final var buf = new InputBuffer(128);
        final var testString = "\uD83D\uDE08 Evil Grin";
        final var testStringBytes = testString.getBytes(StandardCharsets.UTF_16LE);

        buf.addData(new MockReadableChannel(testStringBytes));
        final var s = buf.readString(testStringBytes.length, StandardCharsets.UTF_16LE);
        assertEquals(testString, s);

        buf.addData(new MockReadableChannel(testStringBytes));
        final var s2 = buf.readString(testStringBytes.length, StandardCharsets.ISO_8859_1);
        assertNotEquals(testString, s2);
    }

    //----------------------------------------------------------------------------------------
    // peekString(int numBytes, Charset charset)

    @Test
    void peekStringFailsWhenNoDataIsAvailable() {
        final var buf = new InputBuffer(8);
        assertThrows(BufferUnderflowException.class, () -> buf.peekString(4, StandardCharsets.US_ASCII));
    }

    @Test
    void peekStringFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel("Hi There".getBytes(StandardCharsets.US_ASCII));
        buf.addData(channel);
        buf.skip(7);

        assertThrows(BufferUnderflowException.class, () -> buf.peekString(2, StandardCharsets.US_ASCII));
    }

    @Test
    void peekStringGetsNextBytesAndDoesNotMoveReadPosition() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel("Hi There".getBytes(StandardCharsets.US_ASCII));
        buf.addData(channel);

        String s = buf.peekString(1, StandardCharsets.US_ASCII);
        assertEquals("H", s);
        s = buf.peekString(4, StandardCharsets.US_ASCII);
        assertEquals("Hi T", s);
        s = buf.peekString(8, StandardCharsets.US_ASCII);
        assertEquals("Hi There", s);
    }

    @ParameterizedTest
    @ValueSource(ints = { Integer.MIN_VALUE, -43, -12, -1, 0 })
    void peekStringWithNegativeOrZeroLengthIsAlwaysEmpty(int numBytesToRead) throws IOException {
        final var buf = new InputBuffer(8);
        assertEquals("", buf.peekString(numBytesToRead, StandardCharsets.US_ASCII));

        final var channel = new MockReadableChannel("Hi There".getBytes(StandardCharsets.US_ASCII));
        buf.addData(channel);
        assertEquals("", buf.peekString(numBytesToRead, StandardCharsets.US_ASCII));

        buf.skip(8);
        assertEquals("", buf.peekString(numBytesToRead, StandardCharsets.US_ASCII));
    }

    @Test
    void peekStringWithNullCharsetThrows() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel("Hi There".getBytes(StandardCharsets.US_ASCII));
        buf.addData(channel);

        assertThrows(NullPointerException.class, () -> buf.peekString(4, null));
    }

    @Test
    void peekStringUsesCharset() throws IOException {
        final var buf = new InputBuffer(30);
        final var testString = "\uD83D\uDE08 Evil Grin";
        final var testStringBytes = testString.getBytes(StandardCharsets.UTF_16LE);
        final var channel = new MockReadableChannel(testStringBytes);
        buf.addData(channel);
        final var s = buf.peekString(testStringBytes.length, StandardCharsets.UTF_16LE);
        assertEquals(testString, s);
        final var s2 = buf.peekString(testStringBytes.length, StandardCharsets.ISO_8859_1);
        assertNotEquals(testString, s2);
    }

    //----------------------------------------------------------------------------------------
    // readVersion

    @Test
    void readVersionKnown() throws IOException, ParseException {
        final var buf = new InputBuffer(64);
        final var channel = new MockReadableChannel("HTTP/1.0\rHTTP/1.1\rHTTP/2\rHTTP/2.0\r".getBytes(StandardCharsets.US_ASCII));
        buf.addData(channel);

        assertEquals(HttpVersion.HTTP_1, buf.readVersion());
        buf.skip(1); // skip the \r
        assertEquals(HttpVersion.HTTP_1_1, buf.readVersion());
        buf.skip(1); // skip the \r
        assertEquals(HttpVersion.HTTP_2, buf.readVersion());
        buf.skip(1); // skip the \r
        assertEquals(HttpVersion.HTTP_2, buf.readVersion());
    }

    @Test
    void readVersionUnknown() throws IOException, ParseException {
        final var buf = new InputBuffer(64);
        final var channel = new MockReadableChannel("HTTP/3.0\r".getBytes(StandardCharsets.US_ASCII));
        buf.addData(channel);

        assertNull(buf.readVersion());
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            BAD STRING, 0
            H@TTP/1.0, 1
            HT@TP/1.0, 2
            HTT@P/1.0, 3
            HTTP@/1.0, 4
            HTTP/@1.0, 5
            HTTP/1@.0, 6
            HTTP/1.@0, 7
            """)
    void readVersionParseExceptions(String badString, int badIndex) throws IOException {
        final var buf = new InputBuffer(64);
        final var channel = new MockReadableChannel(badString.getBytes(StandardCharsets.US_ASCII));
        buf.addData(channel);

        try {
            buf.readVersion();
            fail("Parsing '" + badString + "' should have failed at index " + badIndex);
        } catch (ParseException e) {
            assertEquals(badIndex, e.getErrorOffset());
        }
    }

    //----------------------------------------------------------------------------------------
    // read16BitInteger()

    @Test
    void read16BitIntegerFailsWhenNoDataIsAvailable() {
        final var buf = new InputBuffer(8);
        assertThrows(BufferUnderflowException.class, buf::read16BitInteger);
    }

    @Test
    void read16BitIntegerFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
        final var buf = new InputBuffer(4);
        final var channel = new MockReadableChannel(sequentialShorts(2));
        buf.addData(channel);
        buf.skip(4);

        assertThrows(BufferUnderflowException.class, buf::read16BitInteger);
    }

    @Test
    void read16BitIntegerGetsNextBytesAndMovesReadPosition() throws IOException {
        final var buf = new InputBuffer(4);
        final var channel = new MockReadableChannel(shorts(Short.MIN_VALUE, (short) 25));
        buf.addData(channel);

        int s1 = buf.read16BitInteger(); // These are unsigned 16-bit integers!!
        assertEquals(32768, s1);
        int s2 = buf.read16BitInteger();
        assertEquals(25, s2);
    }

    //----------------------------------------------------------------------------------------
    // peek16BitInteger()

    @Test
    void peek16BitIntegerFailsWhenNoDataIsAvailable() {
        final var buf = new InputBuffer(8);
        assertThrows(BufferUnderflowException.class, buf::peek16BitInteger);
    }

    @Test
    void peek16BitIntegerFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
        final var buf = new InputBuffer(4);
        final var channel = new MockReadableChannel(sequentialShorts(2));
        buf.addData(channel);
        buf.skip(4);

        assertThrows(BufferUnderflowException.class, buf::peek16BitInteger);
    }

    @Test
    void peek16BitIntegerGetsNextValueAndDoesNotMoveReadPosition() throws IOException {
        final var buf = new InputBuffer(4);
        final var channel = new MockReadableChannel(shorts(2832, 8832));
        buf.addData(channel);

        int s1 = buf.peek16BitInteger(); // These are unsigned 16-bit integers!!
        assertEquals(2832, s1);
        int s2 = buf.peek16BitInteger();
        assertEquals(2832, s2);
    }

    @Test
    void peek16BitIntegerWithHighBitSet() throws IOException {
        final var buf = new InputBuffer(4);
        final var channel = new MockReadableChannel(shorts(Short.MIN_VALUE, (short) 25));
        buf.addData(channel);

        int s1 = buf.peek16BitInteger(); // These are unsigned 16-bit integers!!
        assertEquals(32768, s1);
    }

    //----------------------------------------------------------------------------------------
    // read24BitInteger()

    @Test
    void read24BitIntegerFailsWhenNoDataIsAvailable() {
        final var buf = new InputBuffer(8);
        assertThrows(BufferUnderflowException.class, buf::read24BitInteger);
    }

    @Test
    void read24BitIntegerFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(int24s(54321, 45678));
        buf.addData(channel);
        buf.skip(6);

        assertThrows(BufferUnderflowException.class, buf::read24BitInteger);
    }

    @Test
    void read24BitIntegerGetsNextBytesAndMovesReadPosition() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(int24s(54321, 45678));
        buf.addData(channel);

        int s1 = buf.read24BitInteger();
        assertEquals(54321, s1);
        int s2 = buf.read24BitInteger();
        assertEquals(45678, s2);
    }

    //----------------------------------------------------------------------------------------
    // peek24BitInteger()

    @Test
    void peek24BitIntegerFailsWhenNoDataIsAvailable() {
        final var buf = new InputBuffer(8);
        assertThrows(BufferUnderflowException.class, buf::peek24BitInteger);
    }

    @Test
    void peek24BitIntegerFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(int24s(54321, 45678));
        buf.addData(channel);
        buf.skip(6);

        assertThrows(BufferUnderflowException.class, buf::peek24BitInteger);
    }

    @Test
    void peek24BitIntegerGetsNextValueAndDoesNotMoveReadPosition() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(int24s(54321, 45678));
        buf.addData(channel);

        int s1 = buf.peek24BitInteger(); // These are unsigned 24-bit integers!!
        assertEquals(54321, s1);
        int s2 = buf.peek24BitInteger();
        assertEquals(54321, s2);
    }

    //----------------------------------------------------------------------------------------
    // read31BitInteger()

    @Test
    void read31BitIntegerFailsWhenNoDataIsAvailable() {
        final var buf = new InputBuffer(8);
        assertThrows(BufferUnderflowException.class, buf::read31BitInteger);
    }

    @Test
    void read31BitIntegerFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(ints(25_983_238, Integer.MAX_VALUE));
        buf.addData(channel);
        buf.skip(6);

        assertThrows(BufferUnderflowException.class, buf::read31BitInteger);
    }

    @Test
    void read31BitIntegerGetsNextBytesAndMovesReadPosition() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(ints(25_983_238, Integer.MAX_VALUE));
        buf.addData(channel);

        int s1 = buf.read31BitInteger();
        assertEquals(25_983_238, s1);
        int s2 = buf.read31BitInteger();
        assertEquals(Integer.MAX_VALUE, s2);
    }

    //----------------------------------------------------------------------------------------
    // peek31BitInteger()

    @Test
    void peek31BitIntegerFailsWhenNoDataIsAvailable() {
        final var buf = new InputBuffer(8);
        assertThrows(BufferUnderflowException.class, buf::peek31BitInteger);
    }

    @Test
    void peek31BitIntegerFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(ints(25_983_238, Integer.MAX_VALUE));
        buf.addData(channel);
        buf.skip(6);

        assertThrows(BufferUnderflowException.class, buf::peek31BitInteger);
    }

    @Test
    void peek31BitIntegerGetsNextValueAndDoesNotMoveReadPosition() throws IOException {
        final var buf = new InputBuffer(8);
        final var channel = new MockReadableChannel(ints(25_983_238, Integer.MAX_VALUE));
        buf.addData(channel);

        int s1 = buf.peek31BitInteger(); // These are unsigned 31-bit integers!!
        assertEquals(25_983_238, s1);
        int s2 = buf.peek31BitInteger();
        assertEquals(25_983_238, s2);
    }

    //----------------------------------------------------------------------------------------
    // peek31BitInteger(int numBytesToLookPast)

    @Test
    void peek31BitIntegerWithLookPastFailsWhenNoDataIsAvailable() {
        final var buf = new InputBuffer(8);
        assertThrows(BufferUnderflowException.class, () -> buf.peek31BitInteger(3));
    }

    @Test
    void peek31BitIntegerWithLookPastFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
        final var buf = new InputBuffer(12);
        final var channel = new MockReadableChannel(ints(555_555_555, 25_983_238, Integer.MAX_VALUE));
        buf.addData(channel);
        buf.skip(10);
        assertThrows(BufferUnderflowException.class, buf::peek31BitInteger);
    }

    @Test
    void peek31BitIntegerWithLookPastGetsNextValueAndDoesNotMoveReadPosition() throws IOException {
        final var buf = new InputBuffer(12);
        final var channel = new MockReadableChannel(ints(555_555_555, 25_983_238, Integer.MAX_VALUE));
        buf.addData(channel);

        int s1 = buf.peek31BitInteger(4); // These are unsigned 31-bit integers!!
        assertEquals(25_983_238, s1);
        int s2 = buf.peek31BitInteger();
        assertEquals(555_555_555, s2);
    }

    //----------------------------------------------------------------------------------------
    // read32BitInteger()

    @Test
    void read32BitIntegerFailsWhenNoDataIsAvailable() {
        final var buf = new InputBuffer(8);
        assertThrows(BufferUnderflowException.class, buf::read32BitInteger);
    }

    @Test
    void read32BitIntegerFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
        final var buf = new InputBuffer(12);
        final var channel = new MockReadableChannel(ints(Integer.MIN_VALUE, 123, Integer.MAX_VALUE));
        buf.addData(channel);
        buf.skip(10);

        assertThrows(BufferUnderflowException.class, buf::read32BitInteger);
    }

    @Test
    void read32BitIntegerGetsNextBytesAndMovesReadPosition() throws IOException {
        final var buf = new InputBuffer(12);
        final var channel = new MockReadableChannel(ints(Integer.MIN_VALUE, 123, Integer.MAX_VALUE));
        buf.addData(channel);

        int s1 = buf.read32BitInteger();
        assertEquals(Integer.MIN_VALUE, s1);
        int s2 = buf.read32BitInteger();
        assertEquals(123, s2);
        int s3 = buf.read32BitInteger();
        assertEquals(Integer.MAX_VALUE, s3);
    }

    //----------------------------------------------------------------------------------------
    // peek32BitInteger()

    @Test
    void peek32BitIntegerFailsWhenNoDataIsAvailable() {
        final var buf = new InputBuffer(8);
        assertThrows(BufferUnderflowException.class, buf::peek32BitInteger);
    }

    @Test
    void peek32BitIntegerFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
        final var buf = new InputBuffer(12);
        final var channel = new MockReadableChannel(ints(Integer.MIN_VALUE, 123, Integer.MAX_VALUE));
        buf.addData(channel);
        buf.skip(10);

        assertThrows(BufferUnderflowException.class, buf::peek32BitInteger);
    }

    @Test
    void peek32BitIntegerGetsNextValueAndDoesNotMoveReadPosition() throws IOException {
        final var buf = new InputBuffer(12);
        final var channel = new MockReadableChannel(ints(Integer.MIN_VALUE, 123, Integer.MAX_VALUE));
        buf.addData(channel);

        int s1 = buf.peek32BitInteger();
        assertEquals(Integer.MIN_VALUE, s1);
        int s2 = buf.peek32BitInteger();
        assertEquals(Integer.MIN_VALUE, s2);
        buf.skip(4);
        int s3 = buf.peek32BitInteger();
        assertEquals(123, s3);
    }

    //----------------------------------------------------------------------------------------
    // read32BitUnsignedInteger()

    @Test
    void read32BitUnsignedIntegerFailsWhenNoDataIsAvailable() {
        final var buf = new InputBuffer(8);
        assertThrows(BufferUnderflowException.class, buf::read32BitUnsignedInteger);
    }

    @Test
    void read32BitUnsignedIntegerFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
        final var buf = new InputBuffer(12);
        final var channel = new MockReadableChannel(uints(
                0b1000_0000_0000_0001,
                0b1111_1111_1111_1111,
                0b0101_1010_1111_0000));
        buf.addData(channel);
        buf.skip(10);

        assertThrows(BufferUnderflowException.class, buf::read32BitUnsignedInteger);
    }

    @Test
    void read32BitUnsignedIntegerGetsNextBytesAndMovesReadPosition() throws IOException {
        final var buf = new InputBuffer(12);
        final var channel = new MockReadableChannel(uints(
                0b1000_0000_0000_0001,
                0b1111_1111_1111_1111,
                0b0101_1010_1111_0000));
        buf.addData(channel);

        long s1 = buf.read32BitUnsignedInteger();
        assertEquals(0b0000_0000_0000_0000_1000_0000_0000_0001, s1);
        long s2 = buf.read32BitUnsignedInteger();
        assertEquals(0b0000_0000_0000_0000_1111_1111_1111_1111, s2);
        long s3 = buf.read32BitUnsignedInteger();
        assertEquals(0b0000_0000_0000_0000_0101_1010_1111_0000, s3);
    }

    //----------------------------------------------------------------------------------------
    // peek32BitInteger()

    @Test
    void peek32BitUnsignedIntegerFailsWhenNoDataIsAvailable() {
        final var buf = new InputBuffer(8);
        assertThrows(BufferUnderflowException.class, buf::peek32BitUnsignedInteger);
    }

    @Test
    void peek32BitUnsignedIntegerFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
        final var buf = new InputBuffer(12);
        final var channel = new MockReadableChannel(uints(
                0b1000_0000_0000_0001,
                0b1111_1111_1111_1111,
                0b0101_1010_1111_0000));
        buf.addData(channel);
        buf.skip(10);

        assertThrows(BufferUnderflowException.class, buf::peek32BitUnsignedInteger);
    }

    @Test
    void peek32BitUnsignedIntegerGetsNextValueAndDoesNotMoveReadPosition() throws IOException {
        final var buf = new InputBuffer(12);
        final var channel = new MockReadableChannel(uints(
                0b1000_0000_0000_0001,
                0b1111_1111_1111_1111,
                0b0101_1010_1111_0000));
        buf.addData(channel);

        long s1 = buf.peek32BitUnsignedInteger();
        assertEquals(0b0000_0000_0000_0000_1000_0000_0000_0001, s1);
        long s2 = buf.peek32BitUnsignedInteger();
        assertEquals(0b0000_0000_0000_0000_1000_0000_0000_0001, s2);
        buf.skip(4);
        long s3 = buf.peek32BitUnsignedInteger();
        assertEquals(0b0000_0000_0000_0000_1111_1111_1111_1111, s3);
    }

    //----------------------------------------------------------------------------------------
    // read64BitLong()

    @Test
    void read64BitLongFailsWhenNoDataIsAvailable() {
        final var buf = new InputBuffer(16);
        assertThrows(BufferUnderflowException.class, buf::read64BitLong);
    }

    @Test
    void read64BitLongFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
        final var buf = new InputBuffer(24);
        final var channel = new MockReadableChannel(longs(
                Long.MIN_VALUE,
                16_283_665_309L,
                Long.MAX_VALUE));
        buf.addData(channel);
        buf.skip(19);

        assertThrows(BufferUnderflowException.class, buf::read64BitLong);
    }

    @Test
    void read64BitLongGetsNextBytesAndMovesReadPosition() throws IOException {
        final var buf = new InputBuffer(24);
        final var channel = new MockReadableChannel(longs(
                Long.MIN_VALUE,
                16_283_665_309L,
                Long.MAX_VALUE));
        buf.addData(channel);

        long s1 = buf.read64BitLong();
        assertEquals(Long.MIN_VALUE, s1);
        long s2 = buf.read64BitLong();
        assertEquals(16_283_665_309L, s2);
        long s3 = buf.read64BitLong();
        assertEquals(Long.MAX_VALUE, s3);
    }

    //----------------------------------------------------------------------------------------
    // peek32BitInteger()

    @Test
    void peek64BitLongFailsWhenNoDataIsAvailable() {
        final var buf = new InputBuffer(8);
        assertThrows(BufferUnderflowException.class, buf::peek64BitLong);
    }

    @Test
    void peek64BitLongFailsWhenNoDataIsAvailableBecauseBufferIsFullAndLooksPastEnd() throws IOException {
        final var buf = new InputBuffer(12);
        final var channel = new MockReadableChannel(longs(
                0b1000_0000_0000_0001,
                0b1111_1111_1111_1111,
                0b0101_1010_1111_0000));
        buf.addData(channel);
        buf.skip(10);

        assertThrows(BufferUnderflowException.class, buf::peek64BitLong);
    }

    @Test
    void peek64BitLongGetsNextValueAndDoesNotMoveReadPosition() throws IOException {
        final var buf = new InputBuffer(24);
        final var channel = new MockReadableChannel(longs(
                Long.MIN_VALUE,
                16_283_665_309L,
                Long.MAX_VALUE));
        buf.addData(channel);

        long s1 = buf.peek64BitLong();
        assertEquals(Long.MIN_VALUE, s1);
        long s2 = buf.peek64BitLong();
        assertEquals(Long.MIN_VALUE, s2);
        buf.skip(8);
        long s3 = buf.peek64BitLong();
        assertEquals(16_283_665_309L, s3);
    }

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

    private byte[] sequentialShorts(int length) {
        try {
            final var bufOut = new ByteArrayOutputStream();
            final var out = new DataOutputStream(bufOut);
            for (short i = 0; i < length; i++) {
                out.writeShort(i);
            }
            return bufOut.toByteArray();
        } catch (IOException e) {
            fail(e);
            return new byte[0]; // Cannot be reached, the previous "fail" will throw.
        }
    }

    private byte[] shorts(int... shorts) {
        try {
            final var bufOut = new ByteArrayOutputStream();
            final var out = new DataOutputStream(bufOut);
            for (int s : shorts) {
                out.writeShort(s);
            }
            return bufOut.toByteArray();
        } catch (IOException e) {
            fail(e);
            return new byte[0]; // Cannot be reached, the previous "fail" will throw.
        }
    }

    private byte[] int24s(int... ints) {
        final var bufOut = new ByteArrayOutputStream();
        for (int i : ints) {
            final var data = new byte[3];
            data[0] = (byte)((i >>> 16) & 0x000000FF);
            data[1] = (byte)((i >>> 8) & 0x000000FF);
            data[2] = (byte)(i & 0x000000FF);
            bufOut.write(data, 0, 3);
        }
        return bufOut.toByteArray();
    }

    private byte[] ints(int... ints) {
        try {
            final var bufOut = new ByteArrayOutputStream();
            final var out = new DataOutputStream(bufOut);
            for (int i : ints) {
                out.writeInt(i);
            }
            return bufOut.toByteArray();
        } catch (IOException e) {
            fail(e);
            return new byte[0]; // Cannot be reached, the previous "fail" will throw.
        }
    }

    private byte[] uints(long... ints) {
        try {
            final var bufOut = new ByteArrayOutputStream();
            final var out = new DataOutputStream(bufOut);
            for (long i : ints) {
                out.writeInt((int) i);
            }
            return bufOut.toByteArray();
        } catch (IOException e) {
            fail(e);
            return new byte[0]; // Cannot be reached, the previous "fail" will throw.
        }
    }

    private byte[] longs(long... longs) {
        try {
            final var bufOut = new ByteArrayOutputStream();
            final var out = new DataOutputStream(bufOut);
            for (long i : longs) {
                out.writeLong(i);
            }
            return bufOut.toByteArray();
        } catch (IOException e) {
            fail(e);
            return new byte[0]; // Cannot be reached, the previous "fail" will throw.
        }
    }

    private byte[] readBytes(InputBuffer buf, int length) {
        var bytes = new byte[length];
        buf.readBytes(bytes, 0, length);
        return bytes;
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
