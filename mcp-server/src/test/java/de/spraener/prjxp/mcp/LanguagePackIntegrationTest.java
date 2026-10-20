package de.spraener.prjxp.mcp;

import de.spraener.prjxp.chuno.language.LanguagePackBroker;
import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackPluginLoader;
import de.spraener.prjxp.common.language.LanguagePackRegistry;
import de.spraener.prjxp.common.language.PluginJarBuilder;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.RetrieverRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-1 Phase 03 (hub path): an external plugin pack must be visible to all
 * engine sides through the shared {@link LanguagePackRegistry} — chunking
 * (LanguagePackBroker), retrieval (RetrieverRegistry) and the grep language
 * filter (GrepSearchService) read the same resolved view.
 */
@SpringBootTest(classes = LanguagePackIntegrationTest.TestConfig.class)
class LanguagePackIntegrationTest {

    @TempDir
    static Path pluginDir;

    @BeforeAll
    static void buildPluginJar() throws IOException {
        PluginJarBuilder.build(pluginDir, "good",
                List.of("de/spraener/prjxp/pluginfix/good/GoodPackProvider.java"),
                "de.spraener.prjxp.pluginfix.good.GoodPackProvider");
    }

    @DynamicPropertySource
    static void pluginDirProperty(DynamicPropertyRegistry registry) {
        registry.add("prjxp.language-packs.dir", () -> pluginDir.toString());
    }

    @Autowired
    LanguagePackRegistry registry;

    @Autowired
    RetrieverRegistry retrieverRegistry;

    @Autowired
    LanguagePackBroker broker;

    @Test
    void pluginPackIsInRegistry() {
        assertThat(registry.all()).extracting(LanguagePack::language)
                .contains("goodlang");
        assertThat(registry.mimeByLanguage()).containsEntry("goodlang", "text/x-goodlang-code");
    }

    @Test
    void pluginRetrieverIsConsideredByRetrievalPath() {
        assertThat(retrieverRegistry.all())
                .as("the plugin's retriever must be part of the retrieval path")
                .anyMatch(r -> r.getClass().getName().contains("GoodRetriever"));
    }

    @Test
    void pluginChunkerFindsPluginFiles() {
        assertThat(broker.findPxChunkers(new File("test.good")).count()).isEqualTo(1);
    }

    @Configuration
    static class TestConfig {

        @Bean
        LanguagePackRegistry languagePackRegistry(ObjectProvider<LanguagePack> builtInPacks,
                                                  ObjectProvider<LanguagePackPluginLoader> loader,
                                                  ObjectProvider<PxChunkDaoProvider> chunkDaoProvider) {
            return new LanguagePackRegistry(builtInPacks, loader, chunkDaoProvider);
        }

        @Bean
        RetrieverRegistry retrieverRegistry(LanguagePackRegistry languagePackRegistry,
                                            List<GoldenRetriever> retrieverBeans) {
            return new RetrieverRegistry(languagePackRegistry, retrieverBeans);
        }

        @Bean
        LanguagePackBroker languagePackBroker(LanguagePackRegistry languagePackRegistry) {
            return new LanguagePackBroker(languagePackRegistry);
        }

        /** So the required List<GoldenRetriever> resolves in this minimal context. */
        @Bean
        GoldenRetriever fakeRetriever() {
            return new GoldenRetriever() {
                @Override
                public StringBuilder buildPromptForFindings(String projectName, List<ScoredChunk> chunks, SearchParams params, Function<String, Boolean>... contextValidators) {
                    return new StringBuilder("fake");
                }

                @Override
                public List<SearchHit> retrieveSearchHits(String projectName, List<ScoredChunk> chunks, Function<String, Boolean>... contextValidators) {
                    return List.of();
                }
            };
        }
    }
}
