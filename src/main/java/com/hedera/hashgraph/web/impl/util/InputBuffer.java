package com.hedera.hashgraph.web.impl.util;

import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.HttpVersion;

import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.Arrays;

/**
 * Abstracts access to the underlying input stream of data coming from the client connection.
 * This class is a highly customized stream, designed for exactly the use case needed by
 * HTTP 1.0/1.1/2.0. The implementation uses a fixed-sized buffer, attempts to be both safe
 * and fast, and generates no garbage.
 * <p>
 * The stream has the concept of a "current position". When any of the "read" methods are called, data
 * is fetched from this current position, if available, and returned, and the current position is advanced
 * past the data that was read. When any of the "peek" methods are called, data is fetched from the
 * current position but <b>without</b> advancing the current position.
 * <p>
 * This implementation also has a concept of an "end position", which is the position into the buffer
 * at which valid data has been read up to. There might be random data beyond this edge. In a normal
 * Java input stream, if you were to attempt to read past this point, it would either return -1 if
 * the stream was closed or it would block until the data is available. <b>This class does neither.</b>
 * Rather, it will throw an exception if you attempt to read beyond the last byte available. You can
 * avoid these exceptions by using the {@link #available(int)} method, to check and see whether there
 * are enough bytes available.
 * <p>
 * For the parsers we are writing, we can easily use the {@link #available(int)} feature to make sure
 * we do not read past the end of the stream without slowing things down.
 * <p>
 * The byte buffer is assumed to be in "network byte order", as defined by the HTTP specifications,
 * which is Big-Endian. Which is the default in Java (because, the network is the computer).
 * <p>
 * Note that this class is not threadsafe, and doesn't need to be, because all code interacting with it
 * does so from the same thread.
 */
public final class InputBuffer {
    /**
     * The buffer into which data is read. The buffer may not be full (i.e. when we last read
     * from the stream, we may have only filled part of the buffer). When the buffer does get
     * full, we move unread data to the front of the buffer and continue filling.
     */
    private final byte[] buffer;

    /**
     * A {@link ByteBuffer} that wraps the {@link #buffer} for the sake of interacting with
     * APIs such as {@link SocketChannel}.
     */
    private final ByteBuffer bb;

    /**
     * The index into the buffer from which we will next read a byte. Initially, this is
     * the start of the buffer, but as bytes are read, it will eventually work its way to
     * the latter part of the buffer, before eventually wrapping around to the start again
     * (assuming there is more data than can be fit into the remaining space on the buffer).
     * This index value is <strong>inclusive</strong>.
     */
    private int readPosition = 0;

    /**
     * The index into the buffer where the very last bytes have been written. This index is
     * <strong>exclusive</strong>, so it is just 1 past the very last byte read.
     */
    private int endPosition = 0;

    /**
     * Track where the {@link #readPosition} was at the time of {@link #mark()}. -1 indicates that
     * it has not been set.
     */
    private int markedPosition = -1;

    /**
     * Creates a new {@code HttpInputStream}.
     *
     * @param size The size of the buffer to use. This must be positive, and generally, should be at least a few
     *             hundred bytes. The size must be larger than any one block of bytes that will be read at some
     *             point later. For example, if a client tries to read a 1K byte array from the stream but the
     *             buffer within the stream is less than 1K, an exception will be thrown.
     */
    public InputBuffer(final int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("The size must be positive");
        }

        this.buffer = new byte[size];
        this.bb = ByteBuffer.wrap(this.buffer);
    }

    /**
     * Called by the {@link Dispatcher} when new data is available for this connection. The stream will
     * attempt to load as much data as possible from the connection into the buffer. If the buffer is
     * already full, then unread data (either starting from the mark position, if set, or the current
     * read position) until the end will be shifted to the start of the buffer, and indexes adjusted
     * accordingly.
     *
     * @param channel The channel from which to read data. Must not be null.
     * @return Whether there is additional data to be read that we didn't get to
     * @throws IOException If the channel is unable to be read.
     *
     * TODO I didn't really want this to be public. Boo.
     */
    public boolean addData(SocketChannel channel) throws IOException {
        assert channel != null : "The dispatcher should never have been able to call this with null for the channel";

        // If the buffer is already full, we need to shift
        if (this.endPosition == buffer.length) {
            shift();
        }

        // Read as many bytes as we can
        try {
            final var numReadBytes = channel.read(bb);
            if (numReadBytes > 1) {
                // We read something, so that is good. Update the endPosition
                endPosition += numReadBytes;
            }
        } catch (SocketException se) {
            se.printStackTrace();
        }

        // If these are equal, then our buffer is full, and there **may** be data left in the channel
        // that we should select later.
        return endPosition == buffer.length;
    }

    /**
     * Called to reset this instance.
     */
    public void reset() {
        this.markedPosition = -1;
        this.readPosition = 0;
        this.endPosition = 0;
    }

    /**
     * Sets the mark position to the current position in the stream. {@link #resetToMark()} will reset
     * the stream position to the mark.
     */
    public void mark() {
        this.markedPosition = readPosition;
    }

    /**
     * Clears the mark. Method is idempotent. Resets the current position in the stream to the marked
     * location.
     *
     * @return the number of bytes between current read position and last marked position
     */
    public int resetToMark() {
        final int numMarkedBytes = this.readPosition - this.markedPosition;
        this.readPosition = this.markedPosition;
        this.markedPosition = -1;
        return numMarkedBytes;
    }

    /**
     * Get the number of bytes between current read position and mark position
     *
     * @return the number of bytes between current read position and last marked position
     */
    public int getNumMarkedBytes() {
        return this.readPosition - this.markedPosition;
    }

    /**
     * Gets whether the given number of bytes are "available" in this stream. If bytes are available,
     * they can be retrieved using any of the "read" or "peek" methods without throwing exceptions.
     * The "available" bytes are those between the current read position, and the end position.
     *
     * @param numBytes The number of bytes to check for availability
     * @return True if that many bytes are available
     */
    public boolean available(int numBytes) {
        return (endPosition - readPosition) >= numBytes;
    }

    /**
     * Skips over {@code length} bytes in the stream. If {@code length} is greater than the
     * available bytes in the stream, an exception is thrown.
     *
     * @param length The length
     * @throws IllegalArgumentException If an attempt is made to skip more bytes than are available.
     */
    public void skip(final int length) {
        assertAvailable(length);
        this.readPosition += length;
    }

    /**
     * Reads a single byte from the stream. The stream's read position is advanced irrevocably.
     *
     * @return A single byte
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public byte readByte() {
        assertAvailable(1);
        return buffer[readPosition++];
    }

    /**
     * Gets a single byte from the stream <strong>without</strong> moving the read position.
     * This method is idempotent.
     *
     * @return A single byte
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public byte peekByte() {
        assertAvailable(1);
        return buffer[readPosition];
    }

    /**
     * Gets a single byte from the stream <strong>without</strong> moving the read position, and by
     * looking past the given number of bytes. This method is idempotent.
     *
     * @return A single byte
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public byte peekByte(int numBytesToLookPast) {
        assertAvailable(1 + numBytesToLookPast);
        return buffer[this.readPosition + numBytesToLookPast];
    }

    /**
     * Reads {@code numBytes} bytes from the stream into the given array. The stream's read position is advanced
     * irrevocably.
     *
     * @param dst A non-null array which must be at least numBytes in length. Data will be placed into the
     *            array starting at index 0.
     * @param dstOffset the offset into the given array into which to read the bytes
     * @param numBytes The number of bytes to read from the underlying stream
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public void readBytes(byte[] dst, int dstOffset, int numBytes) {
        peekBytes(dst, dstOffset, numBytes);
        this.readPosition += numBytes;
    }

    /**
     * Gets {@code numBytes} bytes from the stream and puts them into the given array <strong>without</strong> moving
     * the read position. This method is idempotent.
     *
     * @param dst A non-null array which must be at least numBytes in length. Data will be placed into the
     *            array starting at index 0.
     * @param dstOffset the offset into the given array into which to read the bytes
     * @param numBytes The number of bytes to read from the underlying stream
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public void peekBytes(byte[] dst, int dstOffset, int numBytes) {
        if (numBytes <= 0) {
            return;
        }

        assertAvailable(numBytes);
        System.arraycopy(buffer, this.readPosition, dst, dstOffset, numBytes);
    }

    /**
     * Reads {@code numBytes} bytes from the stream into a new String. The stream's read position is advanced
     * irrevocably.
     *
     * @param numBytes The number of bytes to read from the underlying stream
     * @param charset The character set to use to decode bytes to string, for example StandardCharsets.US_ASCII
     * @return new String containing read bytes converted to string using given character set for decoding
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public String readString(int numBytes, Charset charset) {
        final String string = peekString(numBytes, charset);
        this.readPosition += numBytes;
        return string;
    }

    public HttpVersion readVersion() throws ParseException {
        // "HTTP/1.0"
        if (readByte() != 'H') throw new ParseException("Bad Version",0);
        if (readByte() != 'T') throw new ParseException("Bad Version",1);
        if (readByte() != 'T') throw new ParseException("Bad Version",2);
        if (readByte() != 'P') throw new ParseException("Bad Version",3);
        if (readByte() != '/') throw new ParseException("Bad Version",4);
        int majorChar = readByte();
        int charAfterDigit = peekByte();
        if (charAfterDigit == '\r' && majorChar == '2') {
            // sometimes version is just "HTTP/2" which is not valid but is in use
            return HttpVersion.HTTP_2;
        } else {
            if (readByte() != '.') throw new ParseException("Bad Version", 4);
            int minorChar = readByte();
            if (majorChar == '1' && minorChar == '0') return HttpVersion.HTTP_1;
            if (majorChar == '1' && minorChar == '1') return HttpVersion.HTTP_1_1;
            if (majorChar == '2' && minorChar == '0') return HttpVersion.HTTP_2;
        }
        return null;
    }

    /**
     * Gets {@code numBytes} bytes from the stream and puts them into a new String <strong>without</strong> moving
     * the read position. This method is idempotent.
     *
     * @param numBytes The number of bytes to read from the underlying stream
     * @param charset The character set to use to decode bytes to string, for example StandardCharsets.US_ASCII
     * @return new String containing read bytes converted to string using given character set for decoding
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public String peekString(int numBytes, Charset charset) {
        if (numBytes <= 0) {
            return "";
        }

        assertAvailable(numBytes);
        return new String(buffer, this.readPosition, numBytes, charset);
    }

    /**
     * Reads a single byte from the stream. The stream's read position is advanced <strong>if</strong>
     * there is another byte to be read. If not, rather than throwing an exception, the value -1 is
     * returned.
     *
     * @return A single byte, as an integer.
     */
    public int readByteSafely() {
        return available(1)
                ? (int) buffer[readPosition++]
                : -1;
    }

    /**
     * Reads an unsigned 16-bit integer from the stream. The stream's read position is advanced irrevocably.
     *
     * @return An unsigned 16-bit integer
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public int read16BitInteger() {
        final var i = peek16BitInteger();
        this.readPosition += 2;
        return i;
    }

    /**
     * Gets an unsigned 16-bit integer from the stream <strong>without</strong> moving the read position.
     * This method is idempotent.
     *
     * @return An unsigned 16-bit integer
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public int peek16BitInteger() {
        assertAvailable(2);
        return buffer[readPosition] << 8 | buffer[readPosition + 1];
    }

    /**
     * Reads an unsigned 24-bit integer from the stream. The stream's read position is advanced irrevocably.
     *
     * @return An unsigned 24-bit integer
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public int read24BitInteger() {
        final var i = peek24BitInteger();
        this.readPosition += 3;
        return i;
    }

    /**
     * Gets an unsigned 24-bit integer from the stream <strong>without</strong> moving the read position.
     * This method is idempotent.
     *
     * @return An unsigned 24-bit integer
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public int peek24BitInteger() {
        assertAvailable(3);
        return buffer[readPosition] << 16 | buffer[readPosition + 1] << 8 | buffer[readPosition + 2];
    }

    /**
     * Reads an unsigned 31-bit integer from the stream by reading 32 bits, but ignoring the high order bit.
     * The stream's read position is advanced irrevocably.
     *
     * @return An unsigned 31-bit integer
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public int read31BitInteger() {
        final var i = peek31BitInteger();
        this.readPosition += 4;
        return i;
    }

    /**
     * Gets an unsigned 31-bit integer from the stream <strong>without</strong> moving the read position by reading
     * 32 bits, but ignoring the high order bit. This method is idempotent.
     *
     * @return An unsigned 31-bit integer
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public int peek31BitInteger() {
        int result = peek32BitInteger();
        result &= 0x7FFFFFFF; // Strip off the high bit, setting it to 0
        return result;
    }

    /**
     * Reads a signed 32-bit integer from the stream by reading 32 bits.
     * The stream's read position is advanced irrevocably.
     *
     * @return A signed 32-bit integer
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public int read32BitInteger() {
        final var i = peek32BitInteger();
        this.readPosition += 4;
        return i;
    }

    /**
     * Gets a signed 32-bit integer from the stream <strong>without</strong> moving the read position by reading
     * 32 bits. This method is idempotent.
     *
     * @return A signed 32-bit integer
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public int peek32BitInteger() {
        assertAvailable(4);
        int result = buffer[readPosition] << 24;
        result |= buffer[readPosition + 1] << 16;
        result |= buffer[readPosition + 2] << 8;
        result |= buffer[readPosition + 3];
        return result;
    }

    /**
     * Reads an unsigned 32-bit integer from the stream by reading 32 bits.
     * The stream's read position is advanced irrevocably.
     *
     * @return An unsigned 32-bit integer
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public long read32BitUnsignedInteger() {
        final var i = peek32BitUnsignedInteger();
        this.readPosition += 4;
        return i;
    }

    /**
     * Gets an unsigned 32-bit integer from the stream <strong>without</strong> moving the read position by reading
     * 32 bits. This method is idempotent.
     *
     * @return An unsigned 32-bit integer
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public long peek32BitUnsignedInteger() {
        assertAvailable(4);
        long result = buffer[readPosition] << 24;
        result |= buffer[readPosition + 1] << 16;
        result |= buffer[readPosition + 2] << 8;
        result |= buffer[readPosition + 3];
        return result;
    }

    /**
     * Reads an unsigned 64-bit long from the stream by reading 64 bits.
     * The stream's read position is advanced irrevocably.
     *
     * @return A 64-bit long
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public long read64BitLong() {
        final var i = peek64BitLong();
        this.readPosition += 8;
        return i;
    }

    /**
     * Gets a 64-bit long from the stream <strong>without</strong> moving the read position by reading
     * 64 bits. This method is idempotent.
     *
     * @return A 64-bit long
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public long peek64BitLong() {
        assertAvailable(8);
        long result = asLongNoSignExtend(buffer[readPosition]) << 56;
        result |= asLongNoSignExtend(buffer[readPosition + 1]) << 48;
        result |= asLongNoSignExtend(buffer[readPosition + 2]) << 40;
        result |= asLongNoSignExtend(buffer[readPosition + 3]) << 32;
        result |= buffer[readPosition + 4] << 24;
        result |= buffer[readPosition + 5] << 16;
        result |= buffer[readPosition + 6] << 8;
        result |= buffer[readPosition + 7];
        return result;
    }

    /**
     * Checks whether the stream, starting from the current position in the stream, matches exactly the elements
     * of the given byte array. This method <strong>does not</strong> advance the read position in the stream, so
     * it acts as a peek or look-ahead.
     *
     * @param bytes The bytes to look up. Cannot be null, and must be less than the size configured in this stream's
     *              constructor.
     * @return true if and only if, starting from the current read position, every byte exactly matches the bytes
     *         in the given array.
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     * @throws IllegalArgumentException If the given byte array is too large
     */
    public boolean prefixMatch(final byte[] bytes) {
        if (buffer.length < bytes.length) {
            throw new IllegalArgumentException("The supplied byte array was larger than the buffer of this stream");
        }

        assertAvailable(bytes.length);
        return Arrays.equals(buffer, readPosition, readPosition + bytes.length, bytes, 0, bytes.length);
    }

    /**
     * Shifts the bytes in the buffer such that the byte at {@link #readPosition} becomes the first
     * byte in the {@link #buffer}, and all subsequent bytes until {@link #endPosition} are copied
     * over as well.
     */
    private void shift() {
        var copyFromIndex = readPosition;
        var newReadPosition = 0;
        var newMarkPosition = -1;

        if (markedPosition >= 0) {
            copyFromIndex = markedPosition;
            newReadPosition = readPosition - markedPosition;
            newMarkPosition = 0;
        }

        // Get all bytes from either the marked position (if set) or the read position
        final var unfinishedBytes = endPosition - copyFromIndex;
        System.arraycopy(buffer, copyFromIndex, buffer, 0, unfinishedBytes);

        // The new read position has to maintain its same relative position from the marked position
        readPosition = newReadPosition;
        markedPosition = newMarkPosition;

        // Fix the end position
        endPosition = unfinishedBytes;
        bb.position(endPosition);
    }

    /**
     * Throws an exception if the numBytes is larger than the number of available bytes.
     *
     * @param numBytes The number of bytes to check
     * @throws IllegalArgumentException If numBytes are more than are available.
     */
    private void assertAvailable(int numBytes) {
        if (!available(numBytes)) {
            throw new IllegalArgumentException("There are not " + numBytes + " available in the stream");
        }
    }

    /**
     * Little utility to make sure we can read a byte and shift it into a long without sign extension.
     *
     * @param b The byte to extend
     * @return A long with no signed extension
     */
    private long asLongNoSignExtend(byte b) {
        if (b < 0) {
            return  0x8L | (b & 0x7F);
        } else {
            return b;
        }
    }

    public void init(InputBuffer in) {
        final var length = in.endPosition - in.readPosition;
        System.arraycopy(in.buffer, in.readPosition, buffer, 0, length);
        this.markedPosition = -1;
        this.readPosition = 0;
        this.endPosition = length;
    }
}
