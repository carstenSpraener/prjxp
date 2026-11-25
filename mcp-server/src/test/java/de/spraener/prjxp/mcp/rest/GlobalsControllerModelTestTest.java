package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.mcp.setup.*;
import de.spraener.prjxp.mcp.setup.GlobalsConfigurationService.Versioned;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GlobalsControllerModelTestTest {
    GlobalsService service;
    MockMvc mvc;
    @BeforeEach void start() {
        service = mock(GlobalsService.class);
        mvc = standaloneSetup(new GlobalsController(service)).build();
    }

    @Test void newTestReturnsSetupContractWithResponseVersion() throws Exception {
        when(service.testEmbeddingConfig(any())).thenReturn(new SetupTestResult(true, "ok", 1, null, 64, "receipt", "after"));
        mvc.perform(post("/api/globals/embedding-config/test").header("If-Match", "\"before\"")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"configVersion":"before","configuration":{"type":"OLLAMA","modelName":"draft"}}
                        """))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"after\""))
                .andExpect(jsonPath("$.testId").value("receipt")).andExpect(jsonPath("$.configVersion").value("after"))
                .andExpect(jsonPath("$.dimensions").value(64));
    }

    @Test void testHeadersRequiredAndEnvelopeVersionMustMatch() throws Exception {
        for (String path : new String[]{"embedding-config/test", "embedding-model/test", "chat-models/test"}) {
            mvc.perform(post("/api/globals/" + path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().is(428));
        }
        mvc.perform(post("/api/globals/embedding-config/test").header("If-Match", "\"loaded\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"configVersion\":\"different\",\"configuration\":{}}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIG_CHANGED"));
        verifyNoInteractions(service);
    }

    @Test void legacyTestsRetainOnlyModelTestResultFieldsAndQuotedETag() throws Exception {
        when(service.testEmbeddingModel(eq("version"), any())).thenReturn(new Versioned<>("after",
                ModelTestResult.success("ok", 2, null, 64)));
        when(service.testChatModel(eq("version"), any())).thenReturn(new Versioned<>("after",
                ModelTestResult.failure("redacted failure", 2)));
        mvc.perform(post("/api/globals/embedding-model/test").header("If-Match", "\"version\"")
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"serverType":"ollama","providerUrl":"http://localhost:11434","modelName":"embed","apiKey":"********","timeoutSecs":25}
                        """))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"after\""))
                .andExpect(jsonPath("$.success").value(true)).andExpect(jsonPath("$.dimensions").value(64))
                .andExpect(jsonPath("$.testId").doesNotExist()).andExpect(jsonPath("$.configVersion").doesNotExist());
        mvc.perform(post("/api/globals/chat-models/test").header("If-Match", "version")
                .contentType(MediaType.APPLICATION_JSON).content("{\"stereoType\":\"default\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("redacted failure")).andExpect(jsonPath("$.testId").doesNotExist());
    }

    @Test void setupErrorsUseStableRedactedContractAndIllegalArgumentsNeverReflectMessage() throws Exception {
        when(service.testEmbeddingModel(anyString(), any())).thenThrow(new SetupException("INDEX_REBUILD_REQUIRED", "embedding", 409, "Reindex explicitly"));
        mvc.perform(post("/api/globals/embedding-model/test").header("If-Match", "\"version\"")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.step").value("embedding"))
                .andExpect(jsonPath("$.code").value("INDEX_REBUILD_REQUIRED"));
        when(service.testChatModel(anyString(), any())).thenThrow(new IllegalArgumentException("secret-provider-body"));
        mvc.perform(post("/api/globals/chat-models/test").header("If-Match", "\"version\"")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid Globals request"));
    }
}
