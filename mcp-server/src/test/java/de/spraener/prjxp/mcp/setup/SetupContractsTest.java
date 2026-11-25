package de.spraener.prjxp.mcp.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SetupContractsTest {
    @Test
    void allSharedContractsSerializeAndSecretRequestsHaveRedactedStringRepresentations() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var step = new StepStatus(true, true, true, false, null, null, "2026-11-12T10:00:00Z");
        var status = new SetupStatus("version", false, "NONE", false, "embedding", step, step, step, step);
        var embedding = new EmbeddingConfigRequest("OLLAMA", "http://host", "secret-value", "model", 64, 20, 32, false);
        var chat = new ChatModelRequest("default", "ollama", "model", "secret-value", "http://host", 60, 0.0);
        var github = new GithubConfigRequest("KEY", "secret-value");
        var optimization = new OptimizationRequest(64, 32, 5, 0, 0.8, 5.0, 5.0, 1.0, 20);
        var configuration = new SetupConfiguration(status, embedding, chat, github, optimization, true, true);
        var save = new SetupSaveRequest("version", "embedding", false, "receipt", embedding, null, null, null);
        Object[] values = {step, status, embedding, chat, github, optimization, configuration, save,
                new SetupTestRequest<>("version", chat), new SetupTestResult(true, "ok", 1, "hello", 64, "receipt", "version"),
                new SetupCompleteRequest("version", false), new SetupError("INVALID_REQUEST", "safe", "embedding")};
        for (Object value : values) assertThat(mapper.writeValueAsString(value)).startsWith("{").endsWith("}");
        assertThat(configuration.toString()).doesNotContain("secret-value");
        assertThat(save.toString()).doesNotContain("secret-value");
        SetupException error = new SetupException("code", "embedding", 409, "safe");
        assertThat(error.code()).isEqualTo("code");
        assertThat(error.step()).isEqualTo("embedding");
        assertThat(error.status()).isEqualTo(409);
        assertThatThrownBy(() -> SetupData.canonical(java.util.Map.of("invalid", Double.NaN))).isInstanceOf(SetupException.class);
    }
}
