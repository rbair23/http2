package com.hedera.hashgraph.web.impl.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import static com.hedera.hashgraph.web.impl.http.Http1Constants.*;

/**
 * A OutputStream wrapper that writes bytes written in HTTP Chunked Encoding to the wrapped OutputStream.
 * <a href="https://httpwg.org/specs/rfc9112.html#chunked.encoding">SPEC</a>
 */
public class ChunkedOutputStream extends OutputStream {
    /** OutputStream we are wrapping and writing chunk encoded data to */
    private final OutputStream out;
    /** Data buffer for building up a chunk into for writing */
    private final ByteBuffer buffer;

    /**
     * Create a ChunkedOutStream wrapping given output stream and writing out chunks of size chunkSizeBytes or smaller
     * to the wrapped stream.
     *
     * @param out OutputStream we are wrapping and writing chunk encoded data to
     * @param chunkSizeBytes Data buffer for building up a chunk into for writing
     */
    public ChunkedOutputStream(OutputStream out, int chunkSizeBytes) {
        this.out = Objects.requireNonNull(out);
        if (chunkSizeBytes <= 0) throw new IllegalArgumentException("chunkSizeBytes has to be greater than 0");
        this.buffer = ByteBuffer.allocate(Math.min(MAX_CHUNK_SIZE,chunkSizeBytes));
    }

    @Override
    public void write(int b) throws IOException {
        buffer.put((byte)b);
        // check if chunk buffer is full, and we need to send chunk to wrapped stream
        if (buffer.remaining() == 0) sendChunk();
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        int remainingToWrite = len;
        // if we have more bytes to write than will fit in current chunk then write them in chunks
        while (remainingToWrite > buffer.remaining()) {
            // put as many bytes as we can into buffer to fill current chunk
            final int newOffset = (off + len) - remainingToWrite;
            final int newLength = buffer.remaining();
            buffer.put(b,newOffset,newLength);
            // now send current chunk
            sendChunk();
            // remove written bytes from remainingToWrite
            remainingToWrite -= newLength;
        }
        // now the remaining bytes are less than buffer remaining so we can just add them to buffer
        final int newOffset = (off + len) - remainingToWrite;
        final int newLength = remainingToWrite;
        buffer.put(b, newOffset, newLength);
        if (buffer.remaining() == 0) sendChunk();
    }

    @Override
    public void flush() throws IOException {
        sendChunk();
        out.flush();
    }

    @Override
    public void close() throws IOException {
        sendChunk();
        // write final zero size closing chunk
        out.write('0');
        out.write('\r');
        out.write('\n');
        // write close of chunked stream
        out.write('\r');
        out.write('\n');
        // close wrapped stream
        out.close();
    }

    /**
     * Check if buffer is full
     */
    private void sendChunk() throws IOException {
        if(buffer.position() > 0) {
            buffer.flip();
            // write chunk header
            out.write((Integer.toHexString(buffer.limit()) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            // write chunk data
            out.write(buffer.array(), 0, buffer.limit());
            // write trailing CRLF
            out.write('\r');
            out.write('\n');
            // clear buffer for reuse
            buffer.clear();
        }
    }
}
