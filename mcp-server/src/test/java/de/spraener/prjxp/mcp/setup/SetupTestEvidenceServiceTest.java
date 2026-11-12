package de.spraener.prjxp.mcp.setup;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class SetupTestEvidenceServiceTest {
    private final MutableClock clock = new MutableClock();
    private final SetupTestEvidenceService evidence = new SetupTestEvidenceService(clock);

    @Test
    void canonicalProofBindsSecretsAndAllEffectiveFieldsButNotMapOrderOrNumericSpelling() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("secret", "never-in-proof");
        first.put("temperature", 1);
        first.put("headers", Map.of("a", "one", "b", "two"));
        String token = evidence.issue("embedding", first, 64);
        Map<String, Object> proof = evidence.require(token, "embedding",
                Map.of("headers", Map.of("b", "two", "a", "one"), "temperature", 1.0, "secret", "never-in-proof"));
        assertThat(token).hasSizeGreaterThanOrEqualTo(22).doesNotContain("never-in-proof");
        assertThat(proof.toString()).doesNotContain("never-in-proof");
        assertThat(proof).containsEntry("dimensions", 64).containsKey("tested-at");
        assertThat(new SetupTestEvidenceService(clock).matches(proof, first)).isTrue();
        assertThat(evidence.matches(proof, Map.of("secret", "changed"))).isFalse();
        assertThatThrownBy(() -> evidence.require(token, "chat", first)).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> evidence.require(token, "embedding", Map.of("secret", "changed")))
                .isInstanceOf(SetupException.class);
    }

    @Test
    void receiptsExpireAndAreBoundedButPersistedProofSurvivesRestartAndExpiry() {
        Map<String, Object> candidate = Map.of("model", "test");
        String first = evidence.issue("chat", candidate, null);
        Map<String, Object> proof = evidence.require(first, "chat", candidate);
        for (int i = 0; i < 256; i++) evidence.issue("chat", candidate, null);
        assertThatThrownBy(() -> evidence.require(first, "chat", candidate)).isInstanceOf(SetupException.class);
        String last = evidence.issue("chat", candidate, null);
        clock.now = clock.now.plusSeconds(1800);
        assertThatThrownBy(() -> evidence.require(last, "chat", candidate)).isInstanceOf(SetupException.class);
        assertThat(new SetupTestEvidenceService(clock).matches(proof, candidate)).isTrue();
        assertThatThrownBy(() -> evidence.require("forged", "chat", candidate)).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> evidence.require(null, "chat", candidate)).isInstanceOf(SetupException.class);
    }

    @Test
    void rejectsInvalidResultDimensionsAndCorruptProofs() {
        assertThatThrownBy(() -> evidence.issue("embedding", Map.of(), null)).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> evidence.issue("embedding", Map.of(), 0)).isInstanceOf(SetupException.class);
        assertThatThrownBy(() -> evidence.issue("unknown", Map.of(), null)).isInstanceOf(SetupException.class);
        assertThat(evidence.matches(null, Map.of())).isFalse();
        assertThat(evidence.matches(Map.of("salt", "invalid", "digest", "?"), Map.of())).isFalse();
        assertThat(evidence.matches(Map.of(), Map.of())).isFalse();
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-12T10:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
