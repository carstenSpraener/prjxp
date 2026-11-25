package de.spraener.prjxp.mcp.setup;

import org.springframework.stereotype.Service;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
public class SetupTestEvidenceService {
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Receipt> receipts = new LinkedHashMap<>();

    public SetupTestEvidenceService() { this(Clock.systemUTC()); }
    SetupTestEvidenceService(Clock clock) { this.clock = clock; }

    public synchronized String issue(String step, Map<String, Object> candidate, Integer dimensions) {
        if (!Set.of("embedding", "chat", "github").contains(step)
                || ("embedding".equals(step) && (dimensions == null || dimensions <= 0))) {
            throw new SetupException("TEST_FAILED", step, 400, "Invalid test result");
        }
        purge();
        Map<String, Object> proof = createProof(candidate, dimensions);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes());
        receipts.put(token, new Receipt(step, proof, clock.instant()));
        while (receipts.size() > 256) receipts.remove(receipts.keySet().iterator().next());
        return token;
    }

    public synchronized Map<String, Object> require(String token, String step, Map<String, Object> candidate) {
        purge();
        Receipt receipt = receipts.get(token);
        if (receipt == null || !receipt.step().equals(step) || !matches(receipt.proof(), candidate)) {
            throw new SetupException("TEST_RECEIPT_INVALID", step, 409, "Test receipt is missing, expired or does not match the candidate");
        }
        return SetupData.map(receipt.proof());
    }

    public boolean matches(Map<String, Object> proof, Map<String, Object> candidate) {
        if (proof == null) return false;
        try {
            byte[] salt = Base64.getDecoder().decode((String) proof.get("salt"));
            byte[] expected = Base64.getDecoder().decode((String) proof.get("digest"));
            Instant.parse(Objects.toString(SetupData.get(proof, "tested-at")));
            return salt.length == 16 && expected.length == 32
                    && MessageDigest.isEqual(expected, digest(salt, SetupData.canonical(candidate)));
        } catch (IllegalArgumentException | NullPointerException | ClassCastException error) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Ignoring invalid persisted setup test evidence");
            return false;
        }
    }

    synchronized Map<String, Object> attachIndex(String token, SetupIndexGuard.Validation validation) {
        Receipt receipt = receipts.get(token);
        if (receipt == null) throw new SetupException("TEST_RECEIPT_INVALID", "embedding", 409, "Test receipt expired");
        receipt.proof().put("index-fingerprint", validation.fingerprint());
        receipt.proof().put("index-samples", validation.samples());
        receipt.proof().put("index-cosine", validation.cosine());
        return SetupData.map(receipt.proof());
    }

    static byte[] digest(byte[] salt, byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return digest.digest(content);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private byte[] bytes() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return bytes;
    }

    private void purge() {
        Instant oldest = clock.instant().minusSeconds(1800);
        receipts.values().removeIf(receipt -> !receipt.created().isAfter(oldest));
    }

    Map<String, Object> createProof(Map<String, Object> candidate, Integer dimensions) {
        byte[] salt = bytes();
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("salt", Base64.getEncoder().encodeToString(salt));
        proof.put("digest", Base64.getEncoder().encodeToString(digest(salt, SetupData.canonical(candidate))));
        proof.put("tested-at", clock.instant().toString());
        if (dimensions != null) proof.put("dimensions", dimensions);
        return proof;
    }

    private record Receipt(String step, Map<String, Object> proof, Instant created) {}
}
