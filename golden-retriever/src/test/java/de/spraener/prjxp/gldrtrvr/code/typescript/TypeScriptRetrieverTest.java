package de.spraener.prjxp.gldrtrvr.code.typescript;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.DependencyCompactorTest;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 01: the CLASS_FRAME case must compact the dependency section to maxEntries.
 */
class TypeScriptRetrieverTest {

    private final SearchParams params = new SearchParams(20, 0.85);

    private PxChunk classFrameChunk() {
        return PxChunk.create(
                c -> c.setId("com/example/Foo.ts"),
                c -> c.setContent("export class Foo {}"),
                c -> c.setTotal(1),
                c -> c.getMetadata().put("typescript_code_section", "classFrame"));
    }

    private PxChunkDao mockDao(PxChunk classFrame, String dependencyContent) {
        PxChunkDao dao = mock(PxChunkDao.class);
        // mutable lists: PxChunk.combine sorts them
        when(dao.findById(classFrame.getId())).thenReturn(new java.util.ArrayList<>(List.of(classFrame)));
        if (dependencyContent != null) {
            PxChunk depChunk = PxChunk.create(
                    c -> c.setId(classFrame.getId() + ".dependencies"),
                    c -> c.setContent(dependencyContent));
            when(dao.findById(classFrame.getId() + ".dependencies")).thenReturn(new java.util.ArrayList<>(List.of(depChunk)));
        } else {
            when(dao.findById(classFrame.getId() + ".dependencies")).thenReturn(List.of());
        }
        return dao;
    }

    private TypeScriptRetriever retrieverWith(PxChunkDao dao) {
        PxChunkDaoProvider provider = mock(PxChunkDaoProvider.class);
        when(provider.get("proj")).thenReturn(Optional.of(dao));
        ChunkRankingService rankingService = mock(ChunkRankingService.class);
        when(rankingService.rank(any(PxChunk.class))).thenReturn(1.0);
        // maxDependencyEntries defaults to 10 (Spring injects the @Value in production)
        return new TypeScriptRetriever(provider, rankingService);
    }

    @Test
    void classFrameChunkCompactsDependenciesToMaxEntries() {
        PxChunk classFrame = classFrameChunk();
        TypeScriptRetriever retriever = retrieverWith(mockDao(classFrame, DependencyCompactorTest.dependencyContent(25, 15))); // 40 entries

        String prompt = retriever.buildPromptForFindings("proj", List.of(new ScoredChunk(classFrame, 1.0)), params).toString();

        assertThat(prompt).contains("## Hier ein Rumpf der Klasse com/example/Foo.ts");
        assertThat(prompt).contains("### Hier noch Infos zu den Dependencies innerhalb des Projekts");
        assertThat(DependencyCompactorTest.countEntries(prompt)).isEqualTo(10);
        assertThat(prompt).contains(DependencyCompactorTest.MARKER_30);
    }

    @Test
    void classFrameChunkWithoutDependencyChunkHasNoDependencySection() {
        PxChunk classFrame = classFrameChunk();
        TypeScriptRetriever retriever = retrieverWith(mockDao(classFrame, null));

        String prompt = retriever.buildPromptForFindings("proj", List.of(new ScoredChunk(classFrame, 1.0)), params).toString();

        assertThat(prompt).contains("## Hier ein Rumpf der Klasse com/example/Foo.ts");
        assertThat(prompt).doesNotContain("### Hier noch Infos zu den Dependencies");
    }
}
