package com.hedera.hashgraph.web.impl.http;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static com.hedera.hashgraph.web.impl.http.Http1Constants.*;

/**
 * Handle HTTP Chunk decoding from input stream, just passing raw data though.
 * <a href="https://httpwg.org/specs/rfc9112.html#chunked.encoding">SPEC</a>
 */
public class ChunkedInputStream extends InputStream {
    private final int CR = '\r';
    private final int LF = '\n';
    private final InputStream in;

    private int chunkSize = -1;
    private int chunkDataRead = -1;

    /**
     * Creates a {@code ChunkedInputStream}
     *
     * @param in the underlying input stream, not {@code null}
     */
    public ChunkedInputStream(InputStream in) {
        this.in = Objects.requireNonNull(in);
    }

    @SuppressWarnings("SpellCheckingInspection")
    enum DoubleNewLineState{
        START,
        CR,
        CRLF,
        CRLFCR,
    }
    enum State{
        CHUNK_SIZE,
        CHUNK_DATA,
        DONE
    }
    private State state = State.CHUNK_SIZE;

    @Override
    public void close() throws IOException {
        in.close();
    }

    @Override
    public int read() throws IOException {
        switch (state) {
            case CHUNK_SIZE -> {
                readSizeLine();
                // check for last chunk
                if (chunkSize == -1) {
                    throw new IOException("Problem reading chunk size line");
                } else if (chunkSize == 0) {
                    readTrailerSection();
                    state = State.DONE;
                    return -1;
                } else {
                    // we have finished reading size, now setup for
                    state = State.CHUNK_DATA;
                    chunkDataRead = 0;
                    // not last chunk and size line is read, so read and return first data byte
                    int b = in.read();
                    chunkDataRead++;
                    if (chunkDataRead == chunkSize) {
                        state = State.CHUNK_SIZE;
                    }
                    return b;
                }
            }
            case CHUNK_DATA -> {
                int c = in.read();
                chunkDataRead ++;
                if (chunkDataRead == chunkSize) {
                    // read post data CRLF
                    int c2 = in.read();
                    if (c2 != CR) {
                        throw new IOException("Missing CR after data.");
                    }
                    c2 = in.read();
                    if (c2 != LF) {
                        throw new IOException("Missing CRLF after data.");
                    }
                    // reset ready for next chunk
                    state = State.CHUNK_SIZE;
                    chunkSize = -1;
                    chunkDataRead = -1;
                }
                return c;
            }
            case DONE -> {
                return -1;
            }
        }
        return -1;
    }

    /**
     * (protected for unit tests)
     */
    protected int getChunkSize() {
        return chunkSize;
    }

    /**
     * Reads chuck size line, ignoring chunk extensions
     * <p>
     * (protected for unit tests)
     *
     * @throws IOException If the size line was badly formed
     */
    protected void readSizeLine() throws IOException {
        // read "chunk-size" hex
        int c;
        chunkSize = 0;
        int charsRead = 0;
        while(charsRead <= 7) {
            c = in.read();
            charsRead ++;
            switch(c) {
                case '0' -> chunkSize <<= 4;
                case '1' -> {
                    chunkSize <<= 4;
                    chunkSize |= 1;
                }
                case '2' -> {
                    chunkSize <<= 4;
                    chunkSize |= 2;
                }
                case '3' -> {
                    chunkSize <<= 4;
                    chunkSize |= 3;
                }
                case '4' -> {
                    chunkSize <<= 4;
                    chunkSize |= 4;
                }
                case '5' -> {
                    chunkSize <<= 4;
                    chunkSize |= 5;
                }
                case '6' -> {
                    chunkSize <<= 4;
                    chunkSize |= 6;
                }
                case '7' -> {
                    chunkSize <<= 4;
                    chunkSize |= 7;
                }
                case '8' -> {
                    chunkSize <<= 4;
                    chunkSize |= 8;
                }
                case '9' -> {
                    chunkSize <<= 4;
                    chunkSize |= 9;
                }
                case 'A','a' -> {
                    chunkSize <<= 4;
                    chunkSize |= 0xA;
                }
                case 'B','b' -> {
                    chunkSize <<= 4;
                    chunkSize |= 0xB;
                }
                case 'C','c' -> {
                    chunkSize <<= 4;
                    chunkSize |= 0xC;
                }
                case 'D','d' -> {
                    chunkSize <<= 4;
                    chunkSize |= 0xD;
                }
                case 'E','e' -> {
                    chunkSize <<= 4;
                    chunkSize |= 0xE;
                }
                case 'F','f' -> {
                    chunkSize <<= 4;
                    chunkSize |= 0xF;
                }
                default -> {
                    if (chunkSize == 0 && charsRead == 1) {
                        // we never got a hex digit, oops
                        throw new IOException("Zero length chunk size, last char="+c);
                    } else {
                        // got to end of chunkSizeHex
                        // it must be followed by white space, ';' or CR
                        if (c != CR && c != ';' && c != ' ' && c != '\t') {
                            throw new IOException("Chunk size hex not followed by [CR,SPACE,TAB or ;], but got ["+(char)c+"]");
                        }
                        // read forward till end of line
                        while(c != CR && charsRead < MAX_CHUNK_SIZE_LENGTH) {
                            c = in.read();
                            charsRead ++;
                        }
                        if (c == CR) {
                            // we got CR now check for LR
                            c = in.read();
                            if (c == LF) {
                                // we are done
                                return;
                            } else {
                                throw new IOException("CR not followed by LF");
                            }
                        } else {
                            throw new IOException("Chunk Size line is longer than max length");
                        }
                    }
                }
            }
        }
        if (charsRead == 8) {
            throw new IOException("Chunk size longer than max = 28bit = 268435455 bytes = 255MB");
        }
    }

    /**
     * Reads trailer section ignoring data. Should match regex "\r\n" or ".*\r\n\r\n".
     * <p>
     * (protected for unit tests)
     *
     * @throws IOException If the trailer was badly formed
     */
    protected void readTrailerSection() throws IOException {
        // read "chunk-size" hex
        int c = in.read();
        if (c == CR) {
            // there is no trailers
            c = in.read();
            if (c != LF) {
                throw new IOException("CR not followed by LF");
            }
        } else {
            // we have trailers
            DoubleNewLineState state = DoubleNewLineState.START;
            int count = 0;
            while (count < MAX_CHUNK_FOOTERS_LENGTH) {
                c = in.read();
                switch(state) {
                    case START -> {
                        if (c == CR) {
                            state = DoubleNewLineState.CR;
                        }
                    }
                    case CR -> {
                        if (c == LF) {
                            state = DoubleNewLineState.CRLF;
                        } else {
                            throw new IOException("CR not followed by LF");
                        }
                    }
                    case CRLF -> {
                        if (c == CR) {
                            state = DoubleNewLineState.CRLFCR;
                        } else {
                            state = DoubleNewLineState.START;
                        }
                    }
                    case CRLFCR -> {
                        if (c == LF) {
                            // we have finished trailer section with double CRLF
                            return;
                        } else {
                            throw new IOException("CR not followed by LF");
                        }
                    }
                }
                count ++;
            }
        }
    }
}
