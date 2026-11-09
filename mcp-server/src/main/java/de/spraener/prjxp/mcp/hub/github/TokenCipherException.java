package de.spraener.prjxp.mcp.hub.github;

/** Token encryption/decryption failed (missing key, wrong key, malformed ciphertext). */
public class TokenCipherException extends Exception {
    public TokenCipherException(String message) { super(message); }
    public TokenCipherException(String message, Throwable cause) { super(message, cause); }
}
