package de.spraener.prjxp.mcp.setup;

import de.spraener.prjxp.common.chat.ChatModelSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.nio.file.*;
import java.time.Clock;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SetupControllerTest {
    @TempDir Path directory;
    SetupPersistenceService persistence;
    SetupModelTestService tests;
    MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(directory.resolve("application.yaml"), "prjxp: {embedding: {api-key: controller-private-key}}\n");
        var store = new ConfigurationStore(directory.resolve("application.yaml"), directory.resolve(".env"),
                new MockEnvironment(), Clock.systemUTC());
        var supplier = mock(ChatModelSupplier.class);
        when(supplier.canProvide(any())).thenReturn(true);
        persistence = spy(new SetupPersistenceService(store, new SetupTestEvidenceService(), List.of(supplier)));
        tests = mock(SetupModelTestService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SetupController(persistence, tests))
                .setControllerAdvice(new SetupExceptionAdvice()).build();
    }

    @Test
    void readsConsistentStatusAndMaskedConfigurationWithoutModelCalls() throws Exception {
        mvc.perform(get("/api/setup/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.completed").value(false)).andExpect(jsonPath("$.configVersion").isString());
        mvc.perform(get("/api/setup/configuration")).andExpect(status().isOk())
                .andExpect(jsonPath("$.embedding.apiKey").value("********"))
                .andExpect(jsonPath("$.status.configVersion").isString());
        verifyNoInteractions(tests);
    }

    @Test
    void allThreeTestEndpointsReturnExactSharedResultShapeAndForwardRequest() throws Exception {
        var result = new SetupTestResult(true, "Verified", 10, "Hello", 2, "receipt", version());
        when(tests.testEmbedding(any())).thenReturn(result);
        when(tests.testChat(any())).thenReturn(result);
        var github = new SetupTestResult(false, "No token", 0, null, null, null, version());
        when(tests.testGithub(any())).thenReturn(github);
        for (String step : List.of("embedding", "chat", "github")) {
            mvc.perform(post("/api/setup/test-" + step).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"configVersion\":\"" + version() + "\",\"configuration\":{}}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.configVersion").isString())
                    .andExpect(jsonPath("$.latencyMs").isNumber()).andExpect(jsonPath("$.message").isString())
                    .andExpect(jsonPath("$.success").value(!step.equals("github")));
        }
        verify(tests).testEmbedding(any()); verify(tests).testChat(any()); verify(tests).testGithub(any());
    }

    @Test
    void saveAndCompleteEnforcePayloadReceiptsAndExplicitDefer() throws Exception {
        mvc.perform(post("/api/setup/save").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configVersion\":\"" + version() + "\",\"step\":\"github\",\"skip\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.github.skipped").value(true));
        mvc.perform(post("/api/setup/save").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configVersion\":\"" + version() + "\",\"step\":\"embedding\",\"embedding\":{},\"testId\":\"forged\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEST_RECEIPT_INVALID"));
        mvc.perform(post("/api/setup/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configVersion\":\"" + version() + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SETUP_NOT_VERIFIED"));
        mvc.perform(post("/api/setup/complete").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configVersion\":\"" + version() + "\",\"defer\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.completed").value(true))
                .andExpect(jsonPath("$.completionMode").value("DEFERRED"));
    }

    @Test
    void missingMalformedNullAndForeignBodiesNeverBecomeSuccess() throws Exception {
        for (String path : List.of("test-embedding", "test-chat", "test-github", "save", "complete")) {
            mvc.perform(post("/api/setup/" + path)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
            mvc.perform(post("/api/setup/" + path).contentType(MediaType.APPLICATION_JSON).content("{broken"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(post("/api/setup/test-embedding").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        for (String body : List.of("\"step\":null", "\"step\":\"unknown\"", "\"step\":\"chat\",\"github\":{}",
                "\"step\":\"embedding\",\"skip\":true", "\"step\":\"github\",\"skip\":true,\"github\":{}")) {
            mvc.perform(post("/api/setup/save").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"configVersion\":\"" + version() + "\"," + body + "}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        verifyNoInteractions(tests);
    }

    @Test
    void conflictReadWriteAndInternalFailuresHaveRedactedTypedErrorResponses() throws Exception {
        when(tests.testEmbedding(any())).thenThrow(new SetupException("CONFIG_CHANGED", "embedding", 409, "Reload configuration"));
        mvc.perform(post("/api/setup/test-embedding").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configVersion\":\"" + version() + "\",\"configuration\":{}}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.step").value("embedding"))
                .andExpect(jsonPath("$.code").value("CONFIG_CHANGED"));
        doThrow(new SetupException("CONFIG_WRITE_FAILED", null, 500, "Cannot commit setup configuration"))
                .when(persistence).save(any());
        mvc.perform(post("/api/setup/save").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configVersion\":\"" + version() + "\",\"step\":\"github\",\"skip\":true}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("CONFIG_WRITE_FAILED"));
        doThrow(new IllegalStateException("controller-private-key")).when(persistence).readStatus();
        mvc.perform(get("/api/setup/status")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("controller-private-key"))));
    }

    private String version() { return persistence.readConfiguration().status().configVersion(); }
}
