package de.spraener.prjxp.mcp.hub.github;

import de.spraener.prjxp.mcp.hub.HubProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 06: {@link TokenCipher} — AES-256-GCM + PBKDF2WithHmacSHA256 (mirrors {@code TransferCrypto}),
 * string-level, {@code v1:} format, key from the configured env var. Pure JDK, no new dependency.
 */
class TokenCipherTest {

    private static final String KEY_ENV = "PRJXP_HUB_GITHUB_TOKEN_KEY";

    private static TokenCipher cipherWithKey(String key) {
        MockEnvironment env = new MockEnvironment();
        if (key != null) {
            env.setProperty(KEY_ENV, key);
        }
        return new TokenCipher(new HubProperties(), env);
    }

    @Test
    void encryptThenDecryptRoundTrips() throws Exception {
        TokenCipher cipher = cipherWithKey("s3cret-key");

        assertThat(cipher.decrypt(cipher.encrypt("ghp_abc123"))).isEqualTo("ghp_abc123");
    }

    @Test
    void encryptOutputIsV1WithThreeBase64Segments() throws Exception {
        String ciphertext = cipherWithKey("s3cret-key").encrypt("ghp_abc123");

        assertThat(ciphertext).startsWith("v1:");
        String[] parts = ciphertext.substring(3).split(":");
        assertThat(parts).hasSize(3);
        for (String part : parts) {
            assertThatCode(() -> Base64.getDecoder().decode(part)).doesNotThrowAnyException();
        }
    }

    @Test
    void decryptWithDifferentKeyThrows() throws Exception {
        String ciphertext = cipherWithKey("key-one").encrypt("ghp_abc123");

        assertThatThrownBy(() -> cipherWithKey("key-two").decrypt(ciphertext))
                .isInstanceOf(TokenCipherException.class);
    }

    @Test
    void decryptOfTamperedCiphertextThrows() throws Exception {
        TokenCipher cipher = cipherWithKey("s3cret-key");
        String ciphertext = cipher.encrypt("ghp_abc123");
        String[] parts = ciphertext.substring(3).split(":");
        String ct = parts[2];
        int idx = ct.length() / 2;   // mid-segment: still decodes, but the GCM auth tag no longer matches
        String tampered = "v1:" + parts[0] + ":" + parts[1] + ":"
                + ct.substring(0, idx) + (ct.charAt(idx) == 'A' ? 'B' : 'A') + ct.substring(idx + 1);

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(TokenCipherException.class);
    }

    @Test
    void decryptOfNonV1StringThrows() {
        assertThatThrownBy(() -> cipherWithKey("s3cret-key").decrypt("not-a-ciphertext"))
                .isInstanceOf(TokenCipherException.class)
                .hasMessageContaining("v1");
    }

    @Test
    void encryptWithoutKeyThrowsNamingTheEnvVar() {
        assertThatThrownBy(() -> cipherWithKey(null).encrypt("ghp_abc123"))
                .isInstanceOf(TokenCipherException.class)
                .hasMessageContaining(KEY_ENV);
    }

    @Test
    void decryptWithoutKeyThrowsNamingTheEnvVar() {
        assertThatThrownBy(() -> cipherWithKey(null).decrypt("v1:aa:bb:cc"))
                .isInstanceOf(TokenCipherException.class)
                .hasMessageContaining(KEY_ENV);
    }

    @Test
    void encryptOfNullOrBlankThrows() {
        TokenCipher cipher = cipherWithKey("s3cret-key");

        assertThatThrownBy(() -> cipher.encrypt(null)).isInstanceOf(TokenCipherException.class);
        assertThatThrownBy(() -> cipher.encrypt("   ")).isInstanceOf(TokenCipherException.class);
    }
}
