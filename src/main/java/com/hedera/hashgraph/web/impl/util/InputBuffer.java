package com.hedera.hashgraph.web.impl.util;

import com.hedera.hashgraph.web.impl.Dispatcher;
import com.hedera.hashgraph.web.HttpVersion;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
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
     * Track where the {@link ByteBuffer#position()} was at the time of {@link #mark()}. -1 indicates that
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
        this.bb = ByteBuffer.wrap(this.buffer).limit(0);
    }

    // TODO This was added for testing, I would prefer NOT to expose the buffer at all and find
    //      another way to make testing happy.
    public ByteBuffer getBuffer() {
        return bb;//.asReadOnlyBuffer();
    }

    /**
     * Initialized this {@link InputBuffer} with the data and settings of the given buffer.
     *
     * @param other The buffer to copy. Must not be null.
     */
    public void init(InputBuffer other) {
        final var length = other.bb.remaining();
        if (length > bb.capacity()) {
            throw new ArrayIndexOutOfBoundsException("The source buffer has more data to copy than the target" +
                    "buffer can accept");
        }

        System.arraycopy(other.buffer, other.bb.position(), buffer, 0, length);
        this.markedPosition = -1;
        bb.position(0).limit(length);
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
    public boolean addData(ReadableByteChannel channel) throws IOException {
        assert channel != null : "The dispatcher should never have been able to call this with null for the channel";

        // The channel must be open
        if (!channel.isOpen()) {
            throw new ClosedChannelException();
        }

        // If the buffer is already full, we need to shift
        if (bb.limit() == bb.capacity()) {
            bb.compact();
            // If there is still no room left, then the buffer is full, and we cannot read from the
            // channel, so we need to return true
            if (bb.position() == bb.capacity()) {
                bb.position(0);
                return true;
            } else {
                bb.limit(bb.position()).position(0);
            }
        }

        // Read as many bytes as we can
        final int position = bb.position();
        final int limit = bb.limit();
        bb.position(limit);
        bb.limit(bb.capacity());
        channel.read(bb);
        bb.limit(bb.position());
        bb.position(position);

        // If these are equal, then our buffer is full after reading bytes, and there **may** be data left
        // in the channel that we should select later. We don't know for sure, so we err on the side of
        // caution.
        return bb.limit() == bb.capacity();
    }

    /**
     * Called to init this instance.
     */
    public void reset() {
        this.markedPosition = -1;
        this.bb.clear().limit(0);
    }

    /**
     * Sets the mark position to the current position in the stream. {@link #resetToMark()} will init
     * the stream position to the mark.
     */
    public void mark() {
        this.markedPosition = bb.position();
        bb.mark();
    }

    /**
     * Clears the mark. Method is idempotent. Resets the current position in the stream to the marked
     * location.
     *
     * @return the number of bytes between current read position and last marked position
     */
    public int resetToMark() {
        if (markedPosition != -1) {
            final int numMarkedBytes = getNumMarkedBytes();
            this.markedPosition = -1;
            bb.reset();
            return numMarkedBytes;
        } else {
            return 0;
        }
    }

    /**
     * Get the number of bytes between current read position and mark position
     *
     * @return the number of bytes between current read position and last marked position
     */
    public int getNumMarkedBytes() {
        return bb.position() - this.markedPosition;
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
        return bb.remaining() >= numBytes;
    }

    /**
     * Gets the number of bytes remaining to be read. {@code available(remaining())} will always
     * return true.
     *
     * @return The number of bytes to read.
     */
    public int remaining() {
        return bb.remaining();
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
        bb.position(bb.position() + length);
    }

    /**
     * Reads a single byte from the stream. The stream's read position is advanced irrevocably.
     *
     * @return A single byte
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public byte readByte() {
        assertAvailable(1);
        return bb.get();
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
        return bb.get(bb.position());
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
        return bb.get(bb.position() + numBytesToLookPast);
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
        bb.get(dst, dstOffset, numBytes);
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
        bb.get(bb.position(), dst, dstOffset, numBytes);
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
        if (numBytes <= 0) {
            return "";
        }

        final String string = peekString(numBytes, charset);
        bb.position(bb.position() + numBytes);
        return string;
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
        return new String(buffer, bb.position(), numBytes, charset);
    }

    /**
     * Reads an HTTP version sequence from the buffer, or throws a parse exception if it cannot.
     * The bytes required for the HTTP string must already have been loaded.
     *
     * @return The HttpVersion, or null if the version was semantically correct but didn't match one
     *         of our known versions.
     * @throws ParseException If the bytes being read are not semantically an HTTP version string
     * @throws BufferUnderflowException If enough bytes are not available to parse the complete version string
     */
    public HttpVersion readVersion() throws ParseException {
        // "HTTP/1.0"
        if (readByte() != 'H') throw new ParseException("Bad Version", 0);
        if (readByte() != 'T') throw new ParseException("Bad Version", 1);
        if (readByte() != 'T') throw new ParseException("Bad Version", 2);
        if (readByte() != 'P') throw new ParseException("Bad Version", 3);
        if (readByte() != '/') throw new ParseException("Bad Version", 4);
        int majorChar = readByte();
        int charAfterDigit = peekByte();
        if (charAfterDigit == '\r' && majorChar == '2') {
            // sometimes version is just "HTTP/2" which is not valid but is in use
            return HttpVersion.HTTP_2;
        } else {
            if (!Character.isDigit(majorChar)) throw new ParseException("Bad Major Number", 5);
            if (readByte() != '.') throw new ParseException("Bad Version", 6);
            int minorChar = readByte();
            if (!Character.isDigit(minorChar)) throw new ParseException("Bad Minor Number", 7);
            if (majorChar == '1' && minorChar == '0') return HttpVersion.HTTP_1;
            if (majorChar == '1' && minorChar == '1') return HttpVersion.HTTP_1_1;
            if (majorChar == '2' && minorChar == '0') return HttpVersion.HTTP_2;
        }

        return null;
    }

    /**
     * Reads an unsigned 16-bit integer from the stream. The stream's read position is advanced irrevocably.
     *
     * @return An unsigned 16-bit integer
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public int read16BitInteger() {
        final var i = peek16BitInteger();
        bb.position(bb.position() + 2);
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
        int result = bb.get(bb.position()) << 8;
        result |= bb.get(bb.position() + 1);
        return result & 0x0000FFFF; // Mask off the upper bits, in case of negative sign extension
    }

    /**
     * Reads an unsigned 24-bit integer from the stream. The stream's read position is advanced irrevocably.
     *
     * @return An unsigned 24-bit integer
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public int read24BitInteger() {
        final var i = peek24BitInteger();
        bb.position(bb.position() + 3);
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
        int result = (bb.get(bb.position()) << 16) & 0x00FF0000;
        result |= (bb.get(bb.position() + 1) << 8) & 0x0000FF00;
        result |= (bb.get(bb.position() + 2)) & 0x000000FF;
        return result & 0x00FFFFFF; // Mask off the upper bits, in case of negative sign extension
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
        bb.position(bb.position() + 4);
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
        assertAvailable(4);
        return peek31BitInteger(0);
    }

    /**
     * Gets an unsigned 31-bit integer from the stream <strong>without</strong> moving the read position by reading
     * 32 bits, but ignoring the high order bit. This method is idempotent.
     *
     * @param numBytesToLookPast the number of bytes from the current read position to look past before peeking
     * @return An unsigned 31-bit integer
     * @throws IllegalArgumentException If an attempt is made to read more bytes than are available.
     */
    public int peek31BitInteger(int numBytesToLookPast) {
        assertAvailable(4);
        int result = (bb.get(bb.position() + numBytesToLookPast) << 24) & 0xFF000000;
        result |= (bb.get(bb.position() + numBytesToLookPast + 1) << 16) & 0x00FF0000;
        result |= (bb.get(bb.position() + numBytesToLookPast + 2) << 8) & 0x0000FF00;
        result |= (bb.get(bb.position() + numBytesToLookPast + 3)) & 0x000000FF;
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
        bb.position(bb.position() + 4);
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
        int result = (bb.get(bb.position()) << 24) & 0xFF000000;
        result |= (bb.get(bb.position() + 1) << 16) & 0x00FF0000;
        result |= (bb.get(bb.position() + 2) << 8) & 0x0000FF00;
        result |= (bb.get(bb.position() + 3)) & 0x000000FF;
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
        bb.position(bb.position() + 4);
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
        long result = (bb.get(bb.position()) << 24) & 0xFF000000;
        result |= (bb.get(bb.position() + 1) << 16) & 0x00FF0000;
        result |= (bb.get(bb.position() + 2) << 8) & 0x0000FF00;
        result |= (bb.get(bb.position() + 3)) & 0x000000FF;
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
        bb.position(bb.position() + 8);
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
        long result = ((long) bb.get(bb.position()) << 56) & 0xFF00000000000000L;
        result |= ((long) bb.get(bb.position() + 1) << 48) & 0x00FF000000000000L;
        result |= ((long) bb.get(bb.position() + 2) << 40) & 0x0000FF0000000000L;
        result |= ((long) bb.get(bb.position() + 3) << 32) & 0x000000FF00000000L;
        result |= (bb.get(bb.position() + 4) << 24) & 0x00000000FF000000L;
        result |= (bb.get(bb.position() + 5) << 16) & 0x0000000000FF0000L;
        result |= (bb.get(bb.position() + 6) << 8) & 0x000000000000FF00L;
        result |= (bb.get(bb.position() + 7)) & 0x00000000000000FFL;
        return result;
    }

    /**
     * Throws an exception if the numBytes is larger than the number of available bytes.
     *
     * @param numBytes The number of bytes to check
     * @throws IllegalArgumentException If numBytes are more than are available.
     */
    private void assertAvailable(int numBytes) {
        if (!available(numBytes)) {
            throw new BufferUnderflowException();
        }
    }
}
