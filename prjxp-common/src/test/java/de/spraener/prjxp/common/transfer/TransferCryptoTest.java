package de.spraener.prjxp.common.transfer;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferCryptoTest {
    private static final char[] PASSWORD = "s3cret-passwort".toCharArray();

    private byte[] encryptToBytes(String plaintext) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (var out = TransferCrypto.openEncryptedOutputStream(raw, PASSWORD)) {
            out.write(plaintext.getBytes(StandardCharsets.UTF_8));
        }
        return raw.toByteArray();
    }

    private String decryptToString(byte[] ciphertext) throws IOException {
        try (InputStream in = TransferCrypto.openDecryptedInputStream(new ByteArrayInputStream(ciphertext), PASSWORD)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void roundTrip_encryptThenDecrypt_returnsOriginalPlaintext() throws IOException {
        String plaintext = "Hello PRJXP! äöü ß 42";

        assertThat(decryptToString(encryptToBytes(plaintext))).isEqualTo(plaintext);
    }

    @Test
    void roundTrip_withChunkedWritesAndReads_streamsCorrectly() throws IOException {
        byte[] data = new byte[200_000];
        new java.util.Random(42).nextBytes(data);

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (var out = TransferCrypto.openEncryptedOutputStream(raw, PASSWORD)) {
            for (int i = 0; i < data.length; i += 7_919) {
                int len = Math.min(7_919, data.length - i);
                out.write(data, i, len);
            }
        }

        byte[] ciphertext = raw.toByteArray();
        try (InputStream in = TransferCrypto.openDecryptedInputStream(new ByteArrayInputStream(ciphertext), PASSWORD)) {
            byte[] read = in.readNBytes(data.length);
            assertThat(read).isEqualTo(data);
        }
    }

    @Test
    void roundTrip_withEmptyPlaintext_producesDecryptableStream() throws IOException {
        byte[] ciphertext = encryptToBytes("");

        assertThat(decryptToString(ciphertext)).isEmpty();
    }

    private static int readIntBigEndian(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8) | (bytes[offset + 3] & 0xFF);
    }

    private static int iterationsOffset(byte[] ciphertext) {
        int saltLen = ciphertext[9] & 0xFF;
        int ivLengthOffset = 10 + saltLen;
        int ivLen = ciphertext[ivLengthOffset] & 0xFF;
        return ivLengthOffset + 1 + ivLen;
    }

    @Test
    void header_containsMagicVersionSaltIvAndIterations() throws IOException {
        byte[] ciphertext = encryptToBytes("x");

        assertThat(ciphertext.length).isGreaterThan(43);
        assertThat(new String(ciphertext, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("PRJXPENC");
        assertThat(ciphertext[8]).isEqualTo((byte) 0x01);
        assertThat(ciphertext[9]).isEqualTo((byte) 16);
        int ivLengthOffset = 10 + (ciphertext[9] & 0xFF);
        assertThat(ciphertext[ivLengthOffset]).isEqualTo((byte) 12);
        int iterations = readIntBigEndian(ciphertext, iterationsOffset(ciphertext));
        assertThat(iterations).isEqualTo(200_000);
    }

    @Test
    void header_withCustomIterations_isStoredBigEndian() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (var out = TransferCrypto.openEncryptedOutputStream(raw, PASSWORD, 1_000)) {
            out.write("y".getBytes(StandardCharsets.UTF_8));
        }

        byte[] ciphertext = raw.toByteArray();
        int iterations = readIntBigEndian(ciphertext, iterationsOffset(ciphertext));
        assertThat(iterations).isEqualTo(1_000);

        assertThat(decryptToString(ciphertext)).isEqualTo("y");
    }

    @Test
    void decrypt_withWrongPassword_failsWithAuthTagError() throws IOException {
        byte[] ciphertext = encryptToBytes("geheimer inhalt");

        assertThatThrownBy(() -> decryptToStringWith(ciphertext, "falsches-passwort".toCharArray()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Auth-Tag");
    }

    private String decryptToStringWith(byte[] ciphertext, char[] password) throws IOException {
        try (InputStream in = TransferCrypto.openDecryptedInputStream(new ByteArrayInputStream(ciphertext), password)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void decrypt_withTruncatedCiphertext_fails() throws IOException {
        byte[] ciphertext = encryptToBytes("ein etwas laengerer inhalt fuer den test");

        assertThatThrownBy(() -> decryptToStringWith(
                Arrays.copyOf(ciphertext, ciphertext.length / 2), PASSWORD))
                .isInstanceOf(IOException.class);
    }

    @Test
    void decrypt_withEmptyPassword_throws() {
        assertThatThrownBy(() -> TransferCrypto.openDecryptedInputStream(new ByteArrayInputStream(new byte[10]), new char[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openEncryptedOutputStream_withNullPassword_throws() {
        assertThatThrownBy(() -> TransferCrypto.openEncryptedOutputStream(new ByteArrayOutputStream(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openDecryptedInputStream_withNonEncryptedStream_failsOnMagic() {
        assertThatThrownBy(() -> TransferCrypto.openDecryptedInputStream(
                new ByteArrayInputStream("plain text".getBytes(StandardCharsets.UTF_8)), PASSWORD))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Magic");
    }

    @Test
    void openAuto_withEncryptedStream_decryptsTransparently() throws IOException {
        byte[] ciphertext = encryptToBytes("auto-geheim");

        try (InputStream in = TransferCrypto.openAuto(new ByteArrayInputStream(ciphertext), PASSWORD)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("auto-geheim");
        }
    }

    @Test
    void openAuto_withPlaintextStream_passesThrough() throws IOException {
        byte[] plaintext = "klartext jsonl zeile".getBytes(StandardCharsets.UTF_8);

        try (InputStream in = TransferCrypto.openAuto(new ByteArrayInputStream(plaintext), null)) {
            assertThat(in.readAllBytes()).isEqualTo(plaintext);
        }
    }

    @Test
    void openAuto_withEncryptedStreamButNoPassword_failsClearly() throws IOException {
        byte[] ciphertext = encryptToBytes("geheim");

        assertThatThrownBy(() -> TransferCrypto.openAuto(new ByteArrayInputStream(ciphertext), null))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("kein Passwort");
    }

    @Test
    void ciphertext_differsFromPlaintext_andIsNotReadableAsJsonl() throws IOException {
        String plaintext = "{\"id\":\"1\",\"content\":\"x\"}\n";
        byte[] ciphertext = encryptToBytes(plaintext);

        assertThat(new String(ciphertext, StandardCharsets.UTF_8)).doesNotContain("\"id\"");
    }
}
