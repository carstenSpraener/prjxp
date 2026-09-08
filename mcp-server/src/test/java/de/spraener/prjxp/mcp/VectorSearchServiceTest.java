package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.capability.IdentifierRules;
import de.spraener.prjxp.common.capability.LanguageCapability;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VectorSearchServiceTest {

    @Mock
    PxChunkDaoProvider provider;

    @Mock
    PxChunkDao dao;

    @Mock
    PrjXPConfig cfg;

    @Mock
    SearchCapabilitiesRegistry registry;

    @InjectMocks
    VectorSearchService service;

    private LanguageCapability javaCapability() {
        return new LanguageCapability(
                "java",
                List.of("text/x-java-code"),
                List.of("method", "classFrame"),
                List.of(),
                new IdentifierRules("[A-Za-z_$][A-Za-z0-9_$]*", true));
    }

    @Test
    void passesQueryProjectLanguageAndLimitToDao() {
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        service.search("How does chunking work?", "myproj", null, 10);

        verify(dao).searchVector(
                eq("How does chunking work?"),
                eq(Map.of()),
                eq(10));
    }

    @Test
    void languageFromRegistryBecomesMimeFilter() {
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(registry.forLanguage("java")).thenReturn(Optional.of(javaCapability()));

        service.search("q", "myproj", "java", 10);

        verify(dao).searchVector(
                eq("q"),
                eq(Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/x-java-code")),
                eq(10));
    }

    @Test
    void unknownLanguageAddsNoFilter() {
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        service.search("q", "myproj", "cobol", 10);

        verify(dao).searchVector(
                eq("q"),
                eq(Map.of()),
                eq(10));
    }

    @Test
    void defaultProjectResolvesToActiveProject() {
        ProjectDefinition active = new ProjectDefinition();
        active.setName("cwd");
        when(cfg.getActiveProject()).thenReturn(Optional.of(active));
        when(provider.get("cwd")).thenReturn(Optional.empty());

        service.search("q", "default", null, 10);

        verify(provider).get("cwd");
    }

    @Test
    void unknownProjectReturnsEmptyList() {
        when(provider.get("nope")).thenReturn(Optional.empty());

        List<SearchHit> hits = service.search("q", "nope", null, 10);

        assertThat(hits).isEmpty();
        verifyNoInteractions(dao);
    }

    @Test
    void resultsMappedToSearchHitWithVectorSource() {
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        PxChunk chunk = PxChunk.create(c -> {
            c.setId("chunk-1");
            c.setFile("src/A.java");
            c.setFromLine("10");
            c.setToLine("25");
            c.setContent("x".repeat(300));
        });
        chunk.getMetadata().put("java_code_section", "method");

        when(dao.searchVector(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(chunk, 0.87)));

        List<SearchHit> hits = service.search("How does chunking work?", "myproj", null, 10);

        assertThat(hits).hasSize(1);
        SearchHit hit = hits.get(0);
        assertThat(hit.chunkId()).isEqualTo("chunk-1");
        assertThat(hit.score()).isEqualTo(0.87);
        assertThat(hit.file()).isEqualTo("src/A.java");
        assertThat(hit.lineFrom()).isEqualTo(10);
        assertThat(hit.lineTo()).isEqualTo(25);
        assertThat(hit.source()).isEqualTo("vector");
        assertThat(hit.snippet()).hasSize(240);
        assertThat(hit.metadata()).containsEntry("java_code_section", "method");
    }

    @Test
    void unsupportedStoreReturnsEmptyList() {
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(dao.searchVector(anyString(), anyMap(), anyInt()))
                .thenThrow(new UnsupportedOperationException("no vector store"));

        List<SearchHit> hits = service.search("q", "myproj", null, 10);

        assertThat(hits).isEmpty();
    }
}
