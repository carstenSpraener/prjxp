package de.spraener.prjxp.common.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

public final class SymbolMetadata {
    public static final String SYMBOL_FQN = "symbol_fqn";
    public static final String SYMBOL_NAME = "symbol_name";
    public static final String SYMBOL_TYPE = "symbol_type";
    public static final String SYMBOL_CONTAINER_FQN = "symbol_container_fqn";
    public static final String SYMBOL_SIGNATURE_HASH = "symbol_signature_hash";

    private SymbolMetadata() {
    }

    public static void applyClass(Map<String, String> metadata, String type, String fqn, String simpleName) {
        metadata.put(SYMBOL_FQN, fqn);
        metadata.put(SYMBOL_NAME, simpleName);
        metadata.put(SYMBOL_TYPE, type);
    }

    public static void applyMethod(Map<String, String> metadata, String type,
                                   String containerFqn, String methodName, String declaration) {
        metadata.put(SYMBOL_FQN, containerFqn + "#" + methodName);
        metadata.put(SYMBOL_NAME, methodName);
        metadata.put(SYMBOL_TYPE, type);
        metadata.put(SYMBOL_CONTAINER_FQN, containerFqn);
        metadata.put(SYMBOL_SIGNATURE_HASH, signatureHash(declaration));
    }

    public static String signatureHash(String declaration) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(declaration.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
