package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePacks;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.gldrtrvr.RetrieverRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GrepSearchServiceTest {

    @Mock
    PxChunkDaoProvider provider;

    @Mock
    PxChunkDao dao;

    @Mock
    ProjectRegistry projectRegistry;

    @Mock
    RetrieverRegistry retrieverRegistry;

    @Mock
    GoldenRetriever retriever;

    GrepSearchService service;

    LanguagePack javaPack = new LanguagePack() {
        @Override public String language() { return "java"; }
        @Override public String mimeType() { return "text/x-java-code"; }
    };

    LanguagePack tsPack = new LanguagePack() {
        @Override public String language() { return "typescript"; }
        @Override public String mimeType() { return "text/x-typescript-code"; }
    };

    @BeforeEach void setUp() {
        // No stubbing for retrieverRegistry.all(): Mockito's default answer is an empty List,
        // and most tests never reach the retriever loop (strict stubs would reject an unused stub).
        service = new GrepSearchService(provider, retrieverRegistry, List.of(javaPack), projectRegistry);
    }

    @Test
    void passesQueryProjectLanguageAndLimitToDao() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        service.search("ChunkProcess", "myproj", "java", 10);

        verify(dao).searchFullText(
                eq("ChunkProcess"),
                eq(Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/x-java-code")),
                eq(10));
    }

    @Test
    void defaultProjectUsesDefaultStore() {
        when(projectRegistry.resolve("default")).thenReturn("default");
        when(provider.get("default")).thenReturn(Optional.of(dao));
        when(dao.searchFullText(anyString(), anyMap(), anyInt())).thenReturn(List.of());

        service.search("q", "default", null, 10);

        verify(provider).get("default");
    }

    @Test
    void blankProjectResolvesToActiveProject() {
        when(projectRegistry.resolve("   ")).thenReturn("cwd");
        when(provider.get("cwd")).thenReturn(Optional.of(dao));
        when(dao.searchFullText(anyString(), anyMap(), anyInt())).thenReturn(List.of());

        service.search("q", "   ", null, 10);

        verify(provider).get("cwd");
    }

    @Test
    void unknownProjectThrowsUnknownProjectException() {
        doThrow(new UnknownProjectException("nope", List.of("alpha"))).when(projectRegistry).ensureSearchable("nope");

        assertThatThrownBy(() -> service.search("q", "nope", null, 10))
                .isInstanceOf(UnknownProjectException.class);

        verifyNoInteractions(provider);
        verifyNoInteractions(dao);
    }

    @Test
    void knownProjectWithoutStoreReturnsEmptyList() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.empty());

        List<SearchHit> hits = service.search("q", "myproj", null, 10);

        assertThat(hits).isEmpty();
    }

    @Test
    void resultsMappedToSearchHitWithGrepSource() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        PxChunk chunk = PxChunk.create(c -> {
            c.setId("chunk-1");
            c.setFile("src/A.java");
            c.setFromLine("10");
            c.setToLine("25");
        });
        chunk.getMetadata().put("java_code_section", "method");

        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(chunk, 3.25)));

        List<SearchHit> hits = service.search("ChunkProcess", "myproj", null, 10);

        assertThat(hits).hasSize(1);
        SearchHit hit = hits.get(0);
        assertThat(hit.chunkId()).isEqualTo("src/A.java");
        assertThat(hit.score()).isEqualTo(3.25);
        assertThat(hit.file()).isEqualTo("src/A.java");
        assertThat(hit.lineFrom()).isNull();
        assertThat(hit.lineTo()).isNull();
        assertThat(hit.snippet()).isEmpty();
        assertThat(hit.source()).isEqualTo("grep");
        assertThat(hit.metadata()).isEmpty();
    }

    @Test
    void unsupportedStoreReturnsEmptyList() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenThrow(new UnsupportedOperationException("no lucene"));

        List<SearchHit> hits = service.search("q", "myproj", null, 10);

        assertThat(hits).isEmpty();
    }

    @Test
    void unmappedLanguageUsedAsMimeType() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        service.search("q", "myproj", "text/x-cobol", 10);

        verify(dao).searchFullText(
                eq("q"),
                eq(Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/x-cobol")),
                eq(10));
    }

    @Test
    void snippetContainsMatchedQueryPart() {
        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        String content = "x".repeat(300) + "ChunkProcess" + "y".repeat(300);
        PxChunk chunk = PxChunk.create(c -> {
            c.setId("c1");
            c.setFile("src/A.java");
            c.setContent(content);
        });

        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(chunk, 1.0)));

        when(retrieverRegistry.all()).thenReturn(List.of(retriever));
        when(retriever.buildPromptForFindings(anyString(), anyList(), any(SearchParams.class)))
                .thenReturn(new StringBuilder("...ChunkProcess..."));

        SearchHit hit = service.search("ChunkProcess", "myproj", null, 10).get(0);

        assertThat(hit.snippet()).contains("ChunkProcess");
        assertThat(hit.snippet().length()).isLessThan(content.length());
    }

    // ---- new tests for Phase 04 ----

    @Test
    void tsAliasResolvesToTypescriptMimeType() {
        service = new GrepSearchService(provider, retrieverRegistry, List.of(javaPack, tsPack), projectRegistry);

        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        service.search("q", "myproj", "ts", 10);

        verify(dao).searchFullText(
                eq("q"),
                eq(Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/x-typescript-code")),
                eq(10));
    }

    @Test
    void cobolLanguageUsesExternalPack() {
        // LanguagePacks.merge(empty) still loads external ServiceLoader packs → TestLanguagePack (cobol)
        service = new GrepSearchService(provider, retrieverRegistry, List.of(), projectRegistry);

        when(projectRegistry.resolve("myproj")).thenReturn("myproj");
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        service.search("q", "myproj", "cobol", 10);

        verify(dao).searchFullText(
                eq("q"),
                eq(Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/x-cobol")),
                eq(10));
    }
}
