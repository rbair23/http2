package com.hedera.hashgraph.web.impl;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Objects;

/**
 * Abstracts access to the underlying input stream of data coming from the client.
 * This implementation built on top of NIO {@link SocketChannel}, and buffers data
 * while it is being read. As with any input stream, the data, once read, cannot
 * be read again. However, this API presents "peek" methods allowing the caller to
 * "peek ahead" at some data before officially reading it.
 * <p>
 * Buffer management is an implementation detail. As the client requests additional
 * bytes, if they need to be read from the underlying stream, they will be.
 * <p>
 * As per the spec, section 2.2:
 * <blockquote>
 *     All numeric values are in network byte order. Values are unsigned unless otherwise indicated
 * </blockquote>
 */
public final class HttpInputStream {
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
     * The index into the buffer where the very last bytes that have been written. This index is
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
    protected HttpInputStream(final int size) {
        if (size <= 0) {
            throw new IllegalArgumentException("The size must be positive");
        }

        this.buffer = new byte[size];
        this.bb = ByteBuffer.wrap(this.buffer);
    }

    void addData(SocketChannel channel) throws IOException {
        // If the buffer is already full, we need to shift
        if (this.endPosition == buffer.length) {
            shift();
        }

        // If we haven't read all the bytes we'd like to, but we've
        final var numReadBytes = channel.read(bb);
        if (numReadBytes > 1) {
            // We read something, so that is good. Update the endPosition and return the number of bytes read
            endPosition += numReadBytes;
        }
    }

    public boolean available(int numBytes) {
        return (endPosition - readPosition) >= numBytes;
    }

    /**
     * Skips over {@code length} bytes in the stream. The length may be any valid non-negative value.
     * If the number of bytes to skip is very large, it likely will strip all remaining data from
     * the channel, in which case it will simply terminate.
     *
     * @param length The length
     * @throws IOException Thrown if the underlying channel is closed prematurely or otherwise throws the exception.
     */
    public void skip(final int length) throws IOException {
        if (!available(length)) {
            throw new IllegalArgumentException("Not enough bytes available, please check available first");
        }

        setReadPosition(readPosition + length);
    }

    private void setReadPosition(int value) {
        this.readPosition = value;
    }

    public char readChar() {
        return (char) -1;
    }

    /**
     * Sets the mark position to the current position in the stream. {@link #clearMark()} will reset
     * the stream position to the mark.
     */
    public void mark() {

    }

    /**
     * Clears the mark. Method is idempotent. Resets the current position in the stream to the marked
     * location.
     */
    public void clearMark() {

    }

    /**
     * Reads a single byte from the stream. The stream's read position is advanced irrevocably.
     *
     * @return A single byte
     * @throws IOException Thrown if the underlying channel is closed prematurely or otherwise throws the exception.
     * @throws EOFException If an attempt is made to read a byte past the end of the stream.
     */
    public byte readByte() throws IOException {
        final var b = pollByte();
        setReadPosition(readPosition + 1);
        return b;
    }

    /**
     * Gets a single byte from the stream <strong>without</strong> moving the read position.
     * This method is idempotent.
     *
     * @return A single byte
     * @throws IOException Thrown if the underlying channel is closed prematurely or otherwise throws the exception.
     * @throws EOFException If an attempt is made to read a byte past the end of the stream.
     */
    public byte pollByte() throws IOException {
        return pollByte(0);
    }

    /**
     * Gets a single byte from the stream <strong>without</strong> moving the read position, and by
     * looking past the given number of bytes. This method is idempotent.
     *
     * @return A single byte
     * @throws IOException Thrown if the underlying channel is closed prematurely or otherwise throws the exception.
     * @throws EOFException If an attempt is made to read a byte past the end of the stream.
     */
    public byte pollByte(int numBytesToLookPast) throws IOException {
//        loadIfNeededOrThrow(1 + numBytesToLookPast);
        return buffer[this.readPosition + numBytesToLookPast];
    }

    /**
     * Reads {@code numBytes} bytes from the stream into the given array. The stream's read position is advanced
     * irrevocably.
     *
     * @param array A non-null array which must be at least numBytes in length. Data will be placed into the
     *              array starting at index 0.
     * @param numBytes The number of bytes to read from the underlying stream
     * @throws IOException Thrown if the underlying channel is closed prematurely or otherwise throws the exception.
     * @throws EOFException If an attempt is made to read a byte past the end of the stream.
     */
    public void readBytes(byte[] array, int numBytes) throws IOException {
        pollBytes(array, numBytes);
        this.readPosition += numBytes;
    }

    /**
     * Gets {@code numBytes} bytes from the stream and puts them into the given array <strong>without</strong> moving
     * the read position. This method is idempotent.
     *
     * @param array A non-null array which must be at least numBytes in length. Data will be placed into the
     *              array starting at index 0.
     * @param numBytes The number of bytes to read from the underlying stream
     * @throws IOException Thrown if the underlying channel is closed prematurely or otherwise throws the exception.
     * @throws EOFException If an attempt is made to read a byte past the end of the stream.
     */
    public void pollBytes(byte[] array, int numBytes) throws IOException {
        if (numBytes <= 0) {
            return;
        }

//        loadIfNeededOrThrow(numBytes);
        System.arraycopy(buffer, this.readPosition, array, 0, numBytes);
    }

    /**
     * Reads an unsigned 16-bit integer from the stream. The stream's read position is advanced irrevocably.
     *
     * @return An unsigned 16-bit integer
     * @throws IOException Thrown if the underlying channel is closed prematurely or otherwise throws the exception.
     * @throws EOFException If an attempt is made to read a byte past the end of the stream.
     */
    public int read16BitInteger() throws IOException {
        final var i = poll16BitInteger();
        this.readPosition += 2;
        return i;
    }

    /**
     * Gets an unsigned 16-bit integer from the stream <strong>without</strong> moving the read position.
     * This method is idempotent.
     *
     * @return An unsigned 16-bit integer
     * @throws IOException Thrown if the underlying channel is closed prematurely or otherwise throws the exception.
     * @throws EOFException If an attempt is made to read a byte past the end of the stream.
     */
    public int poll16BitInteger() throws IOException {
//        loadIfNeededOrThrow(2);
        return buffer[readPosition] << 8 | buffer[readPosition + 1];
    }

    /**
     * Reads an unsigned 24-bit integer from the stream. The stream's read position is advanced irrevocably.
     *
     * @return An unsigned 24-bit integer
     * @throws IOException Thrown if the underlying channel is closed prematurely or otherwise throws the exception.
     * @throws EOFException If an attempt is made to read a byte past the end of the stream.
     */
    public int read24BitInteger() throws IOException {
        final var i = poll24BitInteger();
        this.readPosition += 3;
        return i;
    }

    /**
     * Gets an unsigned 24-bit integer from the stream <strong>without</strong> moving the read position.
     * This method is idempotent.
     *
     * @return An unsigned 24-bit integer
     * @throws IOException Thrown if the underlying channel is closed prematurely or otherwise throws the exception.
     * @throws EOFException If an attempt is made to read a byte past the end of the stream.
     */
    public int poll24BitInteger() throws IOException {
//        loadIfNeededOrThrow(3);
        return buffer[readPosition] << 16 | buffer[readPosition + 1] << 8 | buffer[readPosition + 2];
    }

    /**
     * Reads an unsigned 31-bit integer from the stream by reading 32 bits, but ignoring the high order bit.
     * The stream's read position is advanced irrevocably.
     *
     * @return An unsigned 31-bit integer
     * @throws IOException Thrown if the underlying channel is closed prematurely or otherwise throws the exception.
     * @throws EOFException If an attempt is made to read a byte past the end of the stream.
     */
    public int read31BitInteger() throws IOException {
        final var i = poll31BitInteger();
        this.readPosition += 4;
        return i;
    }

    /**
     * Gets an unsigned 31-bit integer from the stream <strong>without</strong> moving the read position by reading
     * 32 bits, but ignoring the high order bit. This method is idempotent.
     *
     * @return An unsigned 31-bit integer
     * @throws IOException Thrown if the underlying channel is closed prematurely or otherwise throws the exception.
     * @throws EOFException If an attempt is made to read a byte past the end of the stream.
     */
    public int poll31BitInteger() throws IOException {
        int result = poll32BitInteger();
        result &= 0x7FFFFFFF; // Strip off the high bit, setting it to 0
        return result;
    }

    /**
     * Reads an unsigned 31-bit integer from the stream by reading 32 bits.
     * The stream's read position is advanced irrevocably.
     *
     * @return An unsigned 31-bit integer
     * @throws IOException Thrown if the underlying channel is closed prematurely or otherwise throws the exception.
     * @throws EOFException If an attempt is made to read a byte past the end of the stream.
     */
    public int read32BitInteger() throws IOException {
        final var i = poll32BitInteger();
        this.readPosition += 4;
        return i;
    }

    /**
     * Gets an unsigned 31-bit integer from the stream <strong>without</strong> moving the read position by reading
     * 32 bits. This method is idempotent.
     *
     * @return An unsigned 32-bit integer
     * @throws IOException Thrown if the underlying channel is closed prematurely or otherwise throws the exception.
     * @throws EOFException If an attempt is made to read a byte past the end of the stream.
     */
    public int poll32BitInteger() throws IOException {
//        loadIfNeededOrThrow(4);
        int result = buffer[readPosition] << 24;
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
     * @throws IOException Thrown if the underlying channel is closed prematurely or otherwise throws the exception.
     * @throws EOFException If an attempt is made to read a byte past the end of the stream.
     */
    public long read64BitLong() throws IOException {
        final var i = poll64BitLong();
        this.readPosition += 8;
        return i;
    }

    /**
     * Gets a 64-bit long from the stream <strong>without</strong> moving the read position by reading
     * 64 bits. This method is idempotent.
     *
     * @return A 64-bit long
     * @throws IOException Thrown if the underlying channel is closed prematurely or otherwise throws the exception.
     * @throws EOFException If an attempt is made to read a byte past the end of the stream.
     */
    public long poll64BitLong() throws IOException {
//        loadIfNeededOrThrow(8);
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

    private long asLongNoSignExtend(byte b) {
        if (b < 0) {
            return  0x8L | (b & 0x7F);
        } else {
            return b;
        }
    }

    /**
     * Checks whether the stream, starting from the current position in the stream, matches exactly the elements
     * of the given byte array. This method <strong>does not</strong> advance the read position in the stream, so
     * it acts as a poll or look-ahead.
     *
     * @param bytes The bytes to look up. Cannot be null, and must be less than the size configured in this stream's
     *              constructor.
     * @return true if and only if, starting from the current read position, every byte exactly matches the bytes
     *         in the given array.
     * @throws IOException Thrown if the underlying channel is closed prematurely or otherwise throws the exception.
     * @throws EOFException If an attempt is made to read a byte past the end of the stream.
     * @throws IllegalArgumentException If the given byte array is too large
     */
    public boolean prefixMatch(final byte[] bytes) throws IOException {
        if (buffer.length < bytes.length) {
            throw new IllegalArgumentException("The supplied byte array was larger than the buffer of this stream");
        }

//        loadIfNeededOrThrow(bytes.length);
        return Arrays.equals(buffer, readPosition, readPosition + bytes.length, bytes, 0, bytes.length);
    }

    /**
     * Shifts the bytes in the buffer such that the byte at {@link #readPosition} becomes the first
     * byte in the {@link #buffer}, and all subsequent bytes until {@link #endPosition} are copied
     * over as well.
     */
    private void shift() {
        final var remainingBytes = this.endPosition - this.readPosition;
        System.arraycopy(buffer, readPosition, buffer, 0, remainingBytes);
        readPosition = 0;
        endPosition = remainingBytes;
        bb.position(endPosition);
    }
}
