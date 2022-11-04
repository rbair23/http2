package com.hedera.hashgraph.web.impl.http;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@SuppressWarnings("SpellCheckingInspection")
public class ChunkedInputStreamTest {
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

}
