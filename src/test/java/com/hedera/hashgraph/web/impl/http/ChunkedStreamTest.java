package com.hedera.hashgraph.web.impl.http;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("SpellCheckingInspection")
public class ChunkedStreamTest {
    private static Stream<Arguments> provideStringsForExampleChunkEncodedData() {
        return Stream.of(
                Arguments.of("6\r\nHello,\r\n6\r\nworld!\r\n0\r\n", "Hello,world!"),
                Arguments.of("F ; extension=value\r\n" + "123456789ABCDEF\r\n"
                        + "E\r\n" + "123456789ABCDE\r\n" + "D; extension=value\r\n"
                        + "123456789ABCD\r\n" + "0; extension=\"value\"\r\n" + "\r\n",
                        "123456789ABCDEF" + "123456789ABCDE" + "123456789ABCD"),
                Arguments.of(// Wikipedia Example
            "4\r\n" +     //      (bytes to send)
                        "Wiki\r\n" + //      (data)
                        "6\r\n" + //         (bytes to send)
                        "pedia \r\n" + //    (data)
                        "E\r\n" + //         (bytes to send)
                        "in \r\n" +
                        "\r\n" +
                        "chunks.\r\n" + //   (data)
                        "0\r\n" + //         (final byte - 0)
                        "\r\n", //           (end message)
                        "Wikipedia in \r\n" +
                                "\r\n" +
                                "chunks.")
            );
    }

    @ParameterizedTest
    @MethodSource("provideStringsForExampleChunkEncodedData")
    void testRead(String inputData, String result) {
        final InputStream is = new ChunkedInputStream(new ByteArrayInputStream(inputData.getBytes()));
        assertDoesNotThrow(() -> {
            StringBuilder buff = new StringBuilder();
            int got;
            while ((got = is.read()) > -1) {
                buff.append((char) got);
            }
            assertEquals(result, buff.toString());
        });
    }

    @ParameterizedTest
    @MethodSource("provideStringsForExampleChunkEncodedData")
    void testReadByteArrayIntInt(String inputData, String result) {
        final InputStream is = new ChunkedInputStream(new ByteArrayInputStream(inputData.getBytes()));
        assertDoesNotThrow(() -> {
            StringBuilder buff = new StringBuilder();
            byte[] b = new byte[12];
            int got;
            while ((got = is.read(b, 2, 7)) > -1)
                buff.append(new String(b, 2, got));
            assertEquals(result, buff.toString());
        });
    }

    private static Stream<Arguments> provideStringsForReadSizeLine() {
        return Stream.of(
                Arguments.of("0\r\n", 0x0),
                Arguments.of("1\r\n", 0x1),
                Arguments.of("A\r\n", 0xA),
                Arguments.of("123\r\n", 0x123),
                Arguments.of("A7F\r\n", 0xA7F),
                Arguments.of("7ABCDEF\r\n", 0x7ABCDEF),
                Arguments.of("7abcdef\r\n", 0x7ABCDEF),
                Arguments.of("123456\r\n", 0x123456),
                Arguments.of("7890\r\n", 0x7890),
                Arguments.of("AA \t;hello = world \t ;  foo =  bar; \r\n", 0xAA),
                Arguments.of("\r\n", -1),
                Arguments.of("AA \t;hello = world \t ;\r  foo =  bar; \r\n", -1),
                Arguments.of("AxA\r\n", -1),
                Arguments.of("A\r\r", -1),
                Arguments.of("A\n\n", -1)
        );
    }

    @ParameterizedTest
    @MethodSource("provideStringsForReadSizeLine")
    void testReadSizeLine(String inputData, int chunkSize) {
        System.out.println("input = [" + inputData+"]");
        if (chunkSize == -1) {
            // expect exception
            assertThrows(IOException.class, () -> {
                final ByteArrayInputStream bin = new ByteArrayInputStream(inputData.getBytes(StandardCharsets.US_ASCII));
                ChunkedInputStream cin = new ChunkedInputStream(bin);
                cin.readSizeLine();
                assertEquals(chunkSize, cin.getChunkSize());
            });
        } else {
            assertDoesNotThrow(() -> {
                final ByteArrayInputStream bin = new ByteArrayInputStream(inputData.getBytes(StandardCharsets.US_ASCII));
                ChunkedInputStream cin = new ChunkedInputStream(bin);
                cin.readSizeLine();
                assertEquals(chunkSize, cin.getChunkSize());
            });
        }
    }

    private static Stream<Arguments> provideStringsForReadTrailerSection() {
        return Stream.of(
                Arguments.of("\r\n"),
                Arguments.of("abc\r\n"),
                Arguments.of(
                        "Header: Yo\r\n" +
                        "ABC:def\r\n" +
                        "WithWhiteSpace: \tanswer \t \r\n" +
                        "\r\n")
        );
    }

    @ParameterizedTest
    @MethodSource("provideStringsForReadTrailerSection")
    void testReadTrailerSection(String inputData) {
        System.out.println("input = [" + inputData+"]");
        assertDoesNotThrow(() -> {
            final ByteArrayInputStream bin = new ByteArrayInputStream(inputData.getBytes(StandardCharsets.US_ASCII));
            ChunkedInputStream cin = new ChunkedInputStream(bin);
            cin.readTrailerSection();
            // we should have read all bytes
            assertEquals(-1, bin.read());
        });
    }

    private static Stream<Arguments> provideStringsForChunkedOutputStream() {
        return Stream.of(
                Arguments.of("Hello,world!", 6, "6\r\nHello,\r\n6\r\nworld!\r\n0\r\n\r\n"),
                Arguments.of(// Wikipedia Example
                        "Wikipedia in \r\n" +
                                "\r\n" +
                                "chunks.",
                        4,
                        "4\r\n" +     //      (bytes to send)
                        "Wiki\r\n" + //      (data)
                        "4\r\n" + //         (bytes to send)
                        "pedi\r\n" + //    (data)
                        "4\r\n" + //         (bytes to send)
                        "a in\r\n" + //    (data)
                        "4\r\n" + //         (bytes to send)
                        " \r\n\r\r\n" + //    (data)
                        "4\r\n" + //         (bytes to send)
                        "\nchu\r\n" + //         (bytes to send)
                        "4\r\n" + //         (bytes to send)
                        "nks.\r\n" +
                        "0\r\n" + //         (final byte - 0)
                        "\r\n" //           (end message)
                    )
        );
    }

    @ParameterizedTest
    @MethodSource("provideStringsForChunkedOutputStream")
    void testChunkedOutputStream(String inputData, int chunkSize, String result) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ChunkedOutputStream cout = new ChunkedOutputStream(bout, chunkSize);
        cout.write(inputData.getBytes(StandardCharsets.US_ASCII));
        cout.close();
        String outputContent = bout.toString(StandardCharsets.US_ASCII);
        assertEquals(Arrays.toString(result.getBytes()), Arrays.toString(bout.toByteArray()));
        assertEquals(result, outputContent);
        // check we can read back with ChunkedInputStream
        final InputStream is = new ChunkedInputStream(new ByteArrayInputStream(outputContent.getBytes()));
        StringBuilder buff = new StringBuilder();
        int got;
        while ((got = is.read()) > -1) {
            buff.append((char) got);
        }
        assertEquals(inputData, buff.toString());
    }

    @ParameterizedTest
    @ValueSource(ints = {-3, 0, 2, 13, 123, 1024, 9000, Integer.MAX_VALUE})
    public void testReadLargeStream(final int chunkSize) throws Exception {
        if (chunkSize <=0) {
            assertThrows(IllegalArgumentException.class, () -> {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ChunkedOutputStream out = new ChunkedOutputStream(baos,chunkSize);
            });
        } else {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ChunkedOutputStream out = new ChunkedOutputStream(baos, chunkSize);
            byte[] buff = new byte[1786]; // odd random number not a power of 2
            for (int c = 0; c < 5; c++) {
                for (int i = 0; i < buff.length; i++) {
                    buff[i] = (byte) ((c * buff.length + i) % 26 + 'A');
                }
                out.write(buff);
            }
            out.close();
            ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
            final ChunkedInputStream cin = new ChunkedInputStream(bais);
            buff = new byte[1024]; // different size to previous
            int got;
            int total = 0;
            while ((got = cin.read(buff)) > 0) {
                System.out.println("Read " + got + " bytes");
                // verify expectation
                for (int i = 0; i < got; i++) {
                    assertEquals((byte) ((total + i) % 26 + 'A'), buff[i], "byte " + (total + i) + " different!");
                }
                total = total + got;
            }
        }
    }
}
