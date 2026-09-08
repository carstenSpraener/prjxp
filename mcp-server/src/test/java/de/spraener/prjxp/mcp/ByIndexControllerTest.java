package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.capability.IdentifierRules;
import de.spraener.prjxp.common.capability.LanguageCapability;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ByIndexController.class)
class ByIndexControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ByIndexSearchService service;

    @MockitoBean
    SearchCapabilitiesRegistry registry;

    private LanguageCapability javaCapability() {
        return new LanguageCapability(
                "java",
                List.of("text/x-java-code"),
                List.of("method", "classFrame"),
                List.of(),
                new IdentifierRules("[A-Za-z_$][A-Za-z0-9_$]*", true));
    }

    @Test
    void missingLanguageReturns400() throws Exception {
        mvc.perform(get("/prjxp/tools/byIndex").param("fqn", "com.example.Foo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missingLanguage"));
    }

    @Test
    void noSearchParamsReturns400() throws Exception {
        mvc.perform(get("/prjxp/tools/byIndex").param("language", "java"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missingSearchParams"));
    }

    @Test
    void unknownLanguageReturns400() throws Exception {
        when(registry.forLanguage("cobol")).thenReturn(Optional.empty());

        mvc.perform(get("/prjxp/tools/byIndex")
                .param("language", "cobol")
                .param("fqn", "com.example.Foo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unknownLanguage"));
    }

    @Test
    void validCallReturns200WithIndexHits() throws Exception {
        when(registry.forLanguage("java")).thenReturn(Optional.of(javaCapability()));

        SearchHit hit = new SearchHit("chunk-1", 1.0, "src/A.java", 10, 20, "snippet text", "index", Map.of());
        when(service.search(any(ByIndexQuery.class))).thenReturn(List.of(hit));

        mvc.perform(get("/prjxp/tools/byIndex")
                .param("language", "java")
                .param("fqn", "com.example.Foo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].chunkId").value("chunk-1"))
                .andExpect(jsonPath("$[0].score").value(1.0))
                .andExpect(jsonPath("$[0].source").value("index"));
    }

    @Test
    void limitAboveMaxIsClampedTo100() throws Exception {
        when(registry.forLanguage("java")).thenReturn(Optional.of(javaCapability()));
        when(service.search(any(ByIndexQuery.class))).thenReturn(List.of());

        mvc.perform(get("/prjxp/tools/byIndex")
                .param("language", "java")
                .param("fqn", "x")
                .param("limit", "500"))
                .andExpect(status().isOk());

        verify(service).search(new ByIndexQuery("java", "x", null, null, null, null, "default", 100));
    }

    @Test
    void defaultLimitIs10() throws Exception {
        when(registry.forLanguage("java")).thenReturn(Optional.of(javaCapability()));
        when(service.search(any(ByIndexQuery.class))).thenReturn(List.of());

        mvc.perform(get("/prjxp/tools/byIndex")
                .param("language", "java")
                .param("fqn", "x"))
                .andExpect(status().isOk());

        verify(service).search(new ByIndexQuery("java", "x", null, null, null, null, "default", 10));
    }

    @Test
    void parametersArePassedThrough() throws Exception {
        when(registry.forLanguage("java")).thenReturn(Optional.of(javaCapability()));
        when(service.search(any(ByIndexQuery.class))).thenReturn(List.of());

        mvc.perform(get("/prjxp/tools/byIndex")
                .param("language", "java")
                .param("fqn", "com.example.Foo#bar")
                .param("symbolType", "method")
                .param("methodName", "bar")
                .param("signatureHash", "abc123")
                .param("containerFqn", "com.example.Foo")
                .param("project", "myproj")
                .param("limit", "5"))
                .andExpect(status().isOk());

        verify(service).search(new ByIndexQuery(
                "java", "com.example.Foo#bar", "method", "bar", "abc123", "com.example.Foo", "myproj", 5));
    }
}
