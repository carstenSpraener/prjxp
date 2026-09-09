package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.capability.IdentifierRules;
import de.spraener.prjxp.common.capability.LanguageCapability;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.model.SymbolMetadata;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ByIndexSearchServiceTest {

    @Mock
    PxChunkDaoProvider provider;

    @Mock
    PxChunkDao dao;

    @Mock
    PrjXPConfig cfg;

    @Mock
    SearchCapabilitiesRegistry registry;

    @InjectMocks
    ByIndexSearchService service;

    private LanguageCapability javaCapability() {
        return new LanguageCapability(
                "java",
                List.of("text/x-java-code"),
                List.of("method", "classFrame"),
                List.of(),
                new IdentifierRules("[A-Za-z_$][A-Za-z0-9_$]*", true));
    }

    private ByIndexQuery query(String language, String fqn, String symbolType,
                               String methodName, String signatureHash, String containerFqn) {
        return new ByIndexQuery(language, fqn, symbolType, methodName, signatureHash, containerFqn, "myproj", 10);
    }

    @Test
    void fqnGoesToSymbolFqnFilter() {
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(registry.forLanguage("java")).thenReturn(Optional.of(javaCapability()));

        service.search(query("java", "com.example.Foo#bar", null, null, null, null));

        verify(dao).searchByIndex(
                eq(Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/x-java-code",
                        SymbolMetadata.SYMBOL_FQN, "com.example.Foo#bar")),
                eq(10));
    }

    @Test
    void methodNameAndContainerFqnNarrowFilters() {
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(registry.forLanguage("java")).thenReturn(Optional.of(javaCapability()));

        service.search(query("java", null, null, "bar", null, "com.example.Foo"));

        verify(dao).searchByIndex(
                eq(Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/x-java-code",
                        SymbolMetadata.SYMBOL_NAME, "bar",
                        SymbolMetadata.SYMBOL_CONTAINER_FQN, "com.example.Foo")),
                eq(10));
    }

    @Test
    void allOptionalParamsMapped() {
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(registry.forLanguage("java")).thenReturn(Optional.of(javaCapability()));

        service.search(query("java", "com.example.Foo#bar", "method", "bar", "abc123", "com.example.Foo"));

        verify(dao).searchByIndex(
                eq(Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/x-java-code",
                        SymbolMetadata.SYMBOL_FQN, "com.example.Foo#bar",
                        SymbolMetadata.SYMBOL_TYPE, "method",
                        SymbolMetadata.SYMBOL_NAME, "bar",
                        SymbolMetadata.SYMBOL_SIGNATURE_HASH, "abc123",
                        SymbolMetadata.SYMBOL_CONTAINER_FQN, "com.example.Foo")),
                eq(10));
    }

    @Test
    void blankOptionalParamsOmitted() {
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(registry.forLanguage("java")).thenReturn(Optional.of(javaCapability()));

        service.search(query("java", "   ", null, null, null, null));

        verify(dao).searchByIndex(
                eq(Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/x-java-code")),
                eq(10));
    }

    @Test
    void limitPassedThrough() {
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(registry.forLanguage("java")).thenReturn(Optional.of(javaCapability()));

        service.search(new ByIndexQuery("java", "com.example.Foo", null, null, null, null, "myproj", 42));

        verify(dao).searchByIndex(anyMap(), eq(42));
    }

    @Test
    void resultsMappedToIndexHits() {
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        PxChunk chunk = PxChunk.create(c -> {
            c.setId("chunk-1");
            c.setFile("src/A.java");
            c.setFromLine("10");
            c.setToLine("25");
            c.setContent("x".repeat(300));
        });

        when(dao.searchByIndex(anyMap(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(chunk, 1.0)));

        List<SearchHit> hits = service.search(query("java", "com.example.Foo", null, null, null, null));

        assertThat(hits).hasSize(1);
        SearchHit hit = hits.get(0);
        assertThat(hit.chunkId()).isEqualTo("chunk-1");
        assertThat(hit.score()).isEqualTo(1.0);
        assertThat(hit.file()).isEqualTo("src/A.java");
        assertThat(hit.lineFrom()).isEqualTo(10);
        assertThat(hit.lineTo()).isEqualTo(25);
        assertThat(hit.source()).isEqualTo("index");
        assertThat(hit.snippet()).hasSize(240);
    }

    @Test
    void resultsStablySortedByChunkId() {
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        PxChunk b = PxChunk.create(c -> c.setId("chunk-b"));
        PxChunk a = PxChunk.create(c -> c.setId("chunk-a"));

        when(dao.searchByIndex(anyMap(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(b, 1.0), new ScoredChunk(a, 1.0)));

        List<SearchHit> hits = service.search(query("java", null, null, "foo", null, null));

        assertThat(hits).extracting(SearchHit::chunkId).containsExactly("chunk-a", "chunk-b");
    }

    @Test
    void unknownProjectReturnsEmptyList() {
        when(provider.get("nope")).thenReturn(Optional.empty());

        List<SearchHit> hits = service.search(
                new ByIndexQuery("java", "com.example.Foo", null, null, null, null, "nope", 10));

        assertThat(hits).isEmpty();
        verifyNoInteractions(dao);
    }

    @Test
    void unsupportedStoreReturnsEmptyList() {
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(registry.forLanguage("java")).thenReturn(Optional.of(javaCapability()));
        when(dao.searchByIndex(anyMap(), anyInt()))
                .thenThrow(new UnsupportedOperationException("no lucene"));

        List<SearchHit> hits = service.search(query("java", "com.example.Foo", null, null, null, null));

        assertThat(hits).isEmpty();
    }

    @Test
    void defaultProjectResolvesToActiveProject() {
        ProjectDefinition active = new ProjectDefinition();
        active.setName("cwd");
        when(cfg.getActiveProject()).thenReturn(Optional.of(active));
        when(provider.get("cwd")).thenReturn(Optional.empty());

        service.search(new ByIndexQuery("java", "com.example.Foo", null, null, null, null, "default", 10));

        verify(provider).get("cwd");
    }
}
