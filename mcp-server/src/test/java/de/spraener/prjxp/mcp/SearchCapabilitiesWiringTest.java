package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.capability.ChunkerSearchCapabilitiesProvider;
import de.spraener.prjxp.common.capability.LanguageCapability;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = SearchCapabilitiesWiringTest.TestConfig.class)
class SearchCapabilitiesWiringTest {

    @Autowired
    SearchCapabilitiesRegistry registry;

    @Configuration
    static class TestConfig {

        @Bean
        SearchCapabilitiesRegistry searchCapabilitiesRegistry(List<ChunkerSearchCapabilitiesProvider> providers) {
            return new SearchCapabilitiesRegistry(providers);
        }

        @Bean
        JavaSearchCapabilitiesProvider javaSearchCapabilitiesProvider() {
            return new JavaSearchCapabilitiesProvider();
        }
    }

    @Test
    void springCollectsProvidersIntoRegistry() {
        assertThat(registry.byLanguage()).containsKey("java");
    }

    @Test
    void registryExposesProviderData() {
        LanguageCapability java = registry.forLanguage("java").orElseThrow();

        assertThat(java.mimeTypes()).contains("text/x-java-code");
        assertThat(java.symbolTypes()).isNotEmpty();
    }
}
