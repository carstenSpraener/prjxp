package de.spraener.prjxp.mcp.ranking;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.retrieval.ranking.RankedItem;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StructureSignalTest {

    private final ChunkRankingService rankingService = mock(ChunkRankingService.class);
    private final StructureSignal signal = new StructureSignal(rankingService);

    private static PxChunk chunk(String id, String file) {
        return PxChunk.create(c -> {
            c.setId(id);
            c.setFile(file);
        });
    }

    @Test
    void reordersTheCandidatesItWasGiven() {
        PxChunk frame = chunk("c1", "A.java");
        PxChunk method = chunk("c2", "B.java");
        when(rankingService.rank(frame)).thenReturn(2.0);
        when(rankingService.rank(method)).thenReturn(5.0);

        List<RankedItem> ranked = signal.rank(Arrays.asList(frame, method));

        assertThat(ranked).extracting(RankedItem::id).containsExactly("c2", "c1");
        assertThat(ranked).hasSize(2);
    }

    @Test
    void normalizesToTheHighestBaseRank() {
        PxChunk method = chunk("c1", "A.java");
        when(rankingService.rank(method)).thenReturn(5.0);

        assertThat(signal.rank(List.of(method)).get(0).score()).isEqualTo(1.0);
    }

    @Test
    void zeroScoreIsDropped() {
        PxChunk dependencyInfo = chunk("c1", "A.java");
        PxChunk method = chunk("c2", "B.java");
        when(rankingService.rank(dependencyInfo)).thenReturn(0.0);
        when(rankingService.rank(method)).thenReturn(5.0);

        assertThat(signal.rank(List.of(dependencyInfo, method)))
                .extracting(RankedItem::id)
                .containsExactly("c2");
    }

    @Test
    void isDeterministicOnEqualRanks() {
        PxChunk b = chunk("c2", "b.java");
        PxChunk a = chunk("c1", "a.java");
        when(rankingService.rank(a)).thenReturn(5.0);
        when(rankingService.rank(b)).thenReturn(5.0);

        assertThat(signal.rank(List.of(b, a))).extracting(RankedItem::id).containsExactly("c1", "c2");
    }

    @Test
    void emptyCandidatesYieldEmptyList() {
        assertThat(signal.rank(List.of())).isEmpty();
        assertThat(signal.rank(null)).isEmpty();
    }

    @Test
    void missingRankingServiceYieldsEmptyList() {
        assertThat(new StructureSignal(null).rank(List.of(chunk("c1", "A.java")))).isEmpty();
    }

    @Test
    void chunksWithoutIdAreSkipped() {
        assertThat(signal.rank(List.of(chunk(null, "A.java")))).isEmpty();
    }
}
