package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
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
class GrepSearchServiceTest {

    @Mock
    PxChunkDaoProvider provider;

    @Mock
    PxChunkDao dao;

    @Mock
    PrjXPConfig cfg;

    @InjectMocks
    GrepSearchService service;

    @Test
    void passesQueryProjectLanguageAndLimitToDao() {
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        service.search("ChunkProcess", "myproj", "java", 10);

        verify(dao).searchFullText(
                eq("ChunkProcess"),
                eq(Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/x-java-code")),
                eq(10));
    }

    @Test
    void defaultProjectResolvesToActiveProject() {
        ProjectDefinition active = new ProjectDefinition();
        active.setName("cwd");
        when(cfg.getActiveProject()).thenReturn(Optional.of(active));
        when(provider.get("cwd")).thenReturn(Optional.of(dao));
        when(dao.searchFullText(anyString(), anyMap(), anyInt())).thenReturn(List.of());

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
    void resultsMappedToSearchHitWithGrepSource() {
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
        assertThat(hit.chunkId()).isEqualTo("chunk-1");
        assertThat(hit.score()).isEqualTo(3.25);
        assertThat(hit.file()).isEqualTo("src/A.java");
        assertThat(hit.lineFrom()).isEqualTo(10);
        assertThat(hit.lineTo()).isEqualTo(25);
        assertThat(hit.source()).isEqualTo("grep");
        assertThat(hit.metadata()).containsEntry("java_code_section", "method");
    }

    @Test
    void unsupportedStoreReturnsEmptyList() {
        when(provider.get("myproj")).thenReturn(Optional.of(dao));
        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenThrow(new UnsupportedOperationException("no lucene"));

        List<SearchHit> hits = service.search("q", "myproj", null, 10);

        assertThat(hits).isEmpty();
    }

    @Test
    void unmappedLanguageUsedAsMimeType() {
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        service.search("q", "myproj", "text/x-cobol", 10);

        verify(dao).searchFullText(
                eq("q"),
                eq(Map.of(PxChunk.PXCHUNK_MIME_TYPE, "text/x-cobol")),
                eq(10));
    }

    @Test
    void snippetContainsMatchedQueryPart() {
        when(provider.get("myproj")).thenReturn(Optional.of(dao));

        String content = "x".repeat(300) + "ChunkProcess" + "y".repeat(300);
        PxChunk chunk = PxChunk.create(c -> {
            c.setId("c1");
            c.setContent(content);
        });

        when(dao.searchFullText(anyString(), anyMap(), anyInt()))
                .thenReturn(List.of(new ScoredChunk(chunk, 1.0)));

        SearchHit hit = service.search("ChunkProcess", "myproj", null, 10).get(0);

        assertThat(hit.snippet()).contains("ChunkProcess");
        assertThat(hit.snippet().length()).isLessThan(content.length());
    }
}
