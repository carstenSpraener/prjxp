package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.common.chat.KIChatModelProvider;
import de.spraener.prjxp.common.model.ModelLister;
import de.spraener.prjxp.mcp.setup.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GlobalsControllerTest {
    @TempDir Path directory;
    GlobalsTestFixture f;
    MockMvc mvc;
    ModelLister models;

    @BeforeEach void start() throws Exception {
        f = new GlobalsTestFixture(directory);
        models = mock(ModelLister.class);
        var service = new GlobalsService(f.globals, mock(SetupModelTestService.class), mock(KIChatModelProvider.class), models);
        mvc = standaloneSetup(new GlobalsController(service)).build();
    }

    String etag() throws Exception {
        return mvc.perform(get("/api/globals/embedding-config")).andExpect(status().isOk())
                .andReturn().getResponse().getHeader("ETag");
    }

    @Test void freshGetHasQuotedETagAndLegacyShapeRemainsSeparate() throws Exception {
        String version = etag();
        assertThat(version).startsWith("\"").endsWith("\"");
        mvc.perform(get("/api/globals/embedding-model")).andExpect(status().isOk())
                .andExpect(header().string("ETag", version)).andExpect(jsonPath("$.serverType").value("ollama"))
                .andExpect(jsonPath("$.modelName").value("original")).andExpect(jsonPath("$.type").doesNotExist());
        mvc.perform(get("/api/globals/chat-models")).andExpect(status().isOk())
                .andExpect(header().string("ETag", version)).andExpect(jsonPath("$[0].prompt").value("instructions"));
    }

    @Test void mutationsRequireIfMatchAndStaleVersionsAreExplicitConflict() throws Exception {
        mvc.perform(put("/api/globals/embedding-config").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(428)).andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        mvc.perform(post("/api/globals/chat-models").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(428));
        mvc.perform(put("/api/globals/chat-models/default").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(428));
        mvc.perform(delete("/api/globals/chat-models/default")).andExpect(status().is(428));
        mvc.perform(put("/api/globals/embedding-model").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().is(428));
        mvc.perform(put("/api/globals/embedding-config").header("If-Match", "\"old\"")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIG_CHANGED"));
    }

    @Test void newEmbeddingUpdateSupportsPartialMaskClearAndTestReceiptHeader() throws Exception {
        var proof = f.embeddingReceipt(f.embeddingChange("tested"));
        mvc.perform(put("/api/globals/embedding-config").header("If-Match", "\"" + proof.configVersion() + "\"")
                .header("X-Setup-Test-Id", proof.testId()).contentType(MediaType.APPLICATION_JSON).content("""
                        {"modelName":"tested","apiKey":"new-secret"}
                        """))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEST_RECEIPT_INVALID"));
        mvc.perform(put("/api/globals/embedding-config").header("If-Match", etag())
                .header("X-Setup-Test-Id", proof.testId()).contentType(MediaType.APPLICATION_JSON).content("{\"modelName\":\"tested\"}"))
                .andExpect(status().isOk()).andExpect(header().exists("ETag")).andExpect(jsonPath("$.modelName").value("tested"));
        mvc.perform(put("/api/globals/embedding-config").header("If-Match", etag())
                .contentType(MediaType.APPLICATION_JSON).content("{\"apiKey\":\"literal\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.apiKey").value(org.hamcrest.Matchers.startsWith("${PRJXP_EMBEDDING_API_KEY_")));
        assertThat(Files.readString(f.yaml)).doesNotContain("literal");
    }

    @Test void legacyPutSupportsOnlyRuntimeProvidersAndKeepsOldSection() throws Exception {
        mvc.perform(put("/api/globals/embedding-model").header("If-Match", etag())
                .contentType(MediaType.APPLICATION_JSON).content("{\"serverType\":\"gemini\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CONFIG"));
        mvc.perform(put("/api/globals/embedding-model").header("If-Match", etag())
                .contentType(MediaType.APPLICATION_JSON).content("{\"modelName\":\"legacy-client\",\"timeoutSecs\":40}"))
                .andExpect(status().isOk()).andExpect(header().exists("ETag"))
                .andExpect(jsonPath("$.modelName").value("legacy-client")).andExpect(jsonPath("$.timeoutSecs").value(40));
        assertThat(Files.readString(f.yaml)).contains("untouched");
    }

    @Test void chatCrudETagsAndAllRequestFieldsRoundtripThroughRealStore() throws Exception {
        var created = mvc.perform(post("/api/globals/chat-models").header("If-Match", etag())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"stereoType":"reviewer","serverType":"ollama","modelName":"model","apiKey":"key",
                         "providerUrl":"http://localhost:11434","timeoutSecs":30,"temperature":0.5,
                         "prompt":"review instructions","args":{"keep":"value","api-key":"argument-key"}}
                        """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.stereoType").value("reviewer"))
                .andExpect(jsonPath("$.prompt").value("review instructions")).andReturn().getResponse();
        var updated = mvc.perform(put("/api/globals/chat-models/reviewer").header("If-Match", created.getHeader("ETag"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"modelName\":\"updated\",\"apiKey\":\"********\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.modelName").value("updated"))
                .andExpect(jsonPath("$.serverType").value("ollama")).andReturn().getResponse();
        mvc.perform(delete("/api/globals/chat-models/reviewer").header("If-Match", updated.getHeader("ETag")))
                .andExpect(status().isOk()).andExpect(header().exists("ETag"));
        mvc.perform(get("/api/globals/chat-models")).andExpect(jsonPath("$.length()").value(2));
    }

    @Test void malformedBodiesAndInvalidETagFormsAreRedacted() throws Exception {
        mvc.perform(put("/api/globals/embedding-config").header("If-Match", etag())
                .contentType(MediaType.APPLICATION_JSON).content("{secret-body"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-body"))));
        for (String header : new String[]{"*", "W/\"version\"", "\"one\",\"two\""}) {
            mvc.perform(put("/api/globals/embedding-config").header("If-Match", header)
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isConflict());
        }
    }

    @Test void serverTypesAndRawListParametersRemainCompatible() throws Exception {
        when(models.listModels("ollama", "http://models", "", 30)).thenReturn(java.util.List.of("available"));
        mvc.perform(get("/api/globals/server-types")).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/globals/embedding-model/models").param("serverType", "ollama")
                .param("providerUrl", "http://models").param("apiKey", ""))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0]").value("available"));
        mvc.perform(get("/api/globals/chat-models/models").param("serverType", "ollama")
                .param("providerUrl", "http://models").param("apiKey", "").param("stereoType", "coder"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0]").value("available"));
    }
}
