package de.spraener.prjxp.mcp.hub.github;

import de.spraener.prjxp.mcp.hub.HubProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts/decrypts a GitHub PAT for at-rest storage in {@code prjxp.yaml}. Mirrors the
 * {@code TransferCrypto} algorithm (AES-256-GCM, 128-bit tag, 12-byte IV, 16-byte salt, key via
 * PBKDF2WithHmacSHA256 @ 200_000 iterations) but operates on a single string. The key is a
 * passphrase read from the env var named by {@code HubProperties.githubTokenKeyEnv} (default
 * {@code PRJXP_HUB_GITHUB_TOKEN_KEY}); the env-var NAME is config, the value is a secret and is
 * never stored. Ciphertext format: {@code v1:<b64salt>:<b64iv>:<b64ct+tag>}.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
public class TokenCipher {

    private static final String VERSION_PREFIX = "v1:";
    private static final int SALT_SIZE = 16;
    private static final int IV_SIZE = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int KEY_SIZE_BITS = 256;
    private static final int KDF_ITERATIONS = 200_000;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final HubProperties props;
    private final Environment env;

    @Autowired
    public TokenCipher(HubProperties props, Environment env) {
        this.props = props;
        this.env = env;
    }

    /** The key passphrase from the configured env var, or {@code null} when unset/blank. */
    char[] resolveKey() {
        String key = env.getProperty(props.getGithubTokenKeyEnv());
        if (key == null || key.isBlank()) {
            return null;
        }
        return key.toCharArray();
    }

    /** Encrypts a plaintext token. Throws when no key is configured or on crypto failure. */
    public String encrypt(String plaintext) throws TokenCipherException {
        if (plaintext == null || plaintext.isBlank()) {
            throw new TokenCipherException("No token to encrypt");
        }
        char[] key = requireKey();
        try {
            byte[] salt = randomBytes(SALT_SIZE);
            byte[] iv = randomBytes(IV_SIZE);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(key, salt), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return VERSION_PREFIX + b64(salt) + ":" + b64(iv) + ":" + b64(ct);
        } catch (GeneralSecurityException e) {
            throw new TokenCipherException("Token encryption failed: " + e.getMessage(), e);
        } finally {
            Arrays.fill(key, (char) 0);
        }
    }

    /** Decrypts a {@code v1:} ciphertext. Throws on bad version/key/format or auth failure. */
    public String decrypt(String ciphertext) throws TokenCipherException {
        if (ciphertext == null || !ciphertext.startsWith(VERSION_PREFIX)) {
            throw new TokenCipherException("Not a v1 token ciphertext");
        }
        char[] key = requireKey();
        try {
            String[] parts = ciphertext.substring(VERSION_PREFIX.length()).split(":");
            if (parts.length != 3) {
                throw new TokenCipherException("Malformed token ciphertext");
            }
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] iv = Base64.getDecoder().decode(parts[1]);
            byte[] ct = Base64.getDecoder().decode(parts[2]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(key, salt), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new TokenCipherException("Token decryption failed (wrong key or corrupted): " + e.getMessage(), e);
        } finally {
            Arrays.fill(key, (char) 0);
        }
    }

    private char[] requireKey() throws TokenCipherException {
        char[] key = resolveKey();
        if (key == null) {
            throw new TokenCipherException("No token key configured — set the "
                    + props.getGithubTokenKeyEnv() + " environment variable");
        }
        return key;
    }

    private static SecretKey deriveKey(char[] password, byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, KDF_ITERATIONS, KEY_SIZE_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
