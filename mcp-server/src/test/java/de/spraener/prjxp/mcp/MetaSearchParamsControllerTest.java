package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.capability.IdentifierRules;
import de.spraener.prjxp.common.capability.LanguageCapability;
import de.spraener.prjxp.common.capability.SearchParamDef;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MetaSearchParamsController.class)
class MetaSearchParamsControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    SearchCapabilitiesRegistry registry;

    private LanguageCapability javaCapability() {
        return new LanguageCapability(
                "java",
                List.of("text/x-java-code"),
                List.of("imports", "methodDoc", "method", "classFrame"),
                List.of(
                        new SearchParamDef("fqn", "Fully qualified name.", false),
                        new SearchParamDef("methodName", "Method name.", false)),
                new IdentifierRules("[A-Za-z_$][A-Za-z0-9_$]*", true));
    }

    private LanguageCapability tsCapability() {
        return new LanguageCapability(
                "ts",
                List.of("text/x-typescript-code"),
                List.of("function"),
                List.of(new SearchParamDef("fqn", "Fully qualified name.", false)),
                new IdentifierRules("[A-Za-z_$][A-Za-z0-9_$]*", true));
    }

    @Test
    void returns200WithVersionAndGlobalParams() throws Exception {
        when(registry.byLanguage()).thenReturn(Map.of("java", javaCapability()));

        mvc.perform(get("/prjxp/tools/meta-search-params"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.0"))
                .andExpect(jsonPath("$.globalParams[?(@.name == 'project')]").exists())
                .andExpect(jsonPath("$.globalParams[?(@.name == 'limit')]").exists())
                .andExpect(jsonPath("$.globalParams[?(@.name == 'mode')]").exists());
    }

    @Test
    void javaProfileHasExpectedFields() throws Exception {
        when(registry.byLanguage()).thenReturn(Map.of("java", javaCapability()));

        mvc.perform(get("/prjxp/tools/meta-search-params"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.languages.java.language").value("java"))
                .andExpect(jsonPath("$.languages.java.mimeTypes[0]").value("text/x-java-code"))
                .andExpect(jsonPath("$.languages.java.symbolTypes", org.hamcrest.Matchers.hasItem("method")))
                .andExpect(jsonPath("$.languages.java.params[?(@.name == 'fqn')]").exists())
                .andExpect(jsonPath("$.languages.java.params[?(@.name == 'methodName')]").exists())
                .andExpect(jsonPath("$.languages.java.identifierRules.pattern").value("[A-Za-z_$][A-Za-z0-9_$]*"))
                .andExpect(jsonPath("$.languages.java.identifierRules.caseSensitive").value(true));
    }

    @Test
    void languagesArePassedThroughFromRegistry() throws Exception {
        when(registry.byLanguage()).thenReturn(Map.of("java", javaCapability(), "ts", tsCapability()));

        mvc.perform(get("/prjxp/tools/meta-search-params"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.languages.java.language").value("java"))
                .andExpect(jsonPath("$.languages.ts.language").value("ts"))
                .andExpect(jsonPath("$.languages.ts.symbolTypes[0]").value("function"));
    }

    @Test
    void emptyRegistryYieldsEmptyLanguages() throws Exception {
        when(registry.byLanguage()).thenReturn(Map.of());

        mvc.perform(get("/prjxp/tools/meta-search-params"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.languages").isEmpty());
    }
}
