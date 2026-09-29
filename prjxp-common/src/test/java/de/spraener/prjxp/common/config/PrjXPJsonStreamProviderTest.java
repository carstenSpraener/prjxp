package de.spraener.prjxp.common.config;

import de.spraener.prjxp.common.transfer.TransferCrypto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrjXPJsonStreamProviderTest {
    @TempDir
    Path tempDir;

    private PrjXPJsonStreamProvider uut() {
        return new PrjXPJsonStreamProvider(new PrjXPConfig());
    }

    @Test
    void getTransferJsonlStream_withPlaintextFile_returnsLines() throws Exception {
        Path file = tempDir.resolve("in.jsonl");
        Files.writeString(file, "{\"id\":\"1\"}\n{\"id\":\"2\"}\n");

        List<String> lines = uut().getTransferJsonlStream(file.toString(), null).toList();

        assertThat(lines).containsExactly("{\"id\":\"1\"}", "{\"id\":\"2\"}");
    }

    @Test
    void getTransferJsonlStream_withEncryptedFileAndPassword_returnsDecryptedLines() throws Exception {
        String plaintext = "{\"id\":\"1\"}\n{\"id\":\"2\"}\n";
        byte[] ciphertext = encrypt(plaintext, "pw".toCharArray());
        Path file = tempDir.resolve("in.jsonl.enc");
        Files.write(file, ciphertext);

        List<String> lines = uut().getTransferJsonlStream(file.toString(), "pw".toCharArray()).toList();

        assertThat(lines).containsExactly("{\"id\":\"1\"}", "{\"id\":\"2\"}");
    }

    @Test
    void getTransferJsonlStream_withEncryptedFileWithoutPassword_failsClearly() throws Exception {
        byte[] ciphertext = encrypt("{\"id\":\"1\"}\n", "pw".toCharArray());
        Path file = tempDir.resolve("in.jsonl.enc");
        Files.write(file, ciphertext);

        assertThatThrownBy(() -> uut().getTransferJsonlStream(file.toString(), null).toList())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("kein Passwort");
    }

    private byte[] encrypt(String plaintext, char[] password) throws IOException {
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        try (var out = TransferCrypto.openEncryptedOutputStream(raw, password)) {
            out.write(plaintext.getBytes(StandardCharsets.UTF_8));
        }
        return raw.toByteArray();
    }
}
