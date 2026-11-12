package de.spraener.prjxp.mcp.hub.github;

/** Token encryption/decryption failed (missing key, wrong key, malformed ciphertext). */
public class TokenCipherException extends Exception {
    private final String code;
    public TokenCipherException(String message) { this("CRYPTO_FAILED", message, null); }
    public TokenCipherException(String message, Throwable cause) { this("CRYPTO_FAILED", message, cause); }
    public TokenCipherException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
    public String code() { return code; }
}
