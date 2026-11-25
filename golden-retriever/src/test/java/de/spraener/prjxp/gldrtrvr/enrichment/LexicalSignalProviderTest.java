package de.spraener.prjxp.gldrtrvr.enrichment;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.store.PxChunkDao;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LexicalSignalProviderTest {

    private final LexicalSignalProvider provider = new LexicalSignalProvider();

    @Test
    void passesPromptAndWindowToTheIndex() {
        PxChunkDao dao = mock(PxChunkDao.class);
        ScoredChunk hit = new ScoredChunk(PxChunk.create(c -> {
            c.setId("c1");
            c.setFile("A.java");
        }), 3.0);
        when(dao.searchCandidates("Preload", Map.of(), 50)).thenReturn(List.of(hit));

        assertThat(provider.candidates(dao, "Preload", Map.of(), 50)).containsExactly(hit);
    }

    @Test
    void emptyOrBlankPromptAsksNoIndex() {
        PxChunkDao dao = mock(PxChunkDao.class);

        assertThat(provider.candidates(dao, "", Map.of(), 50)).isEmpty();
        assertThat(provider.candidates(dao, "  ", Map.of(), 50)).isEmpty();
        assertThat(provider.candidates(dao, null, Map.of(), 50)).isEmpty();
        verifyNoInteractions(dao);
    }

    @Test
    void nonPositiveWindowAsksNoIndex() {
        PxChunkDao dao = mock(PxChunkDao.class);

        assertThat(provider.candidates(dao, "Preload", Map.of(), 0)).isEmpty();
        verifyNoInteractions(dao);
    }

    @Test
    void missingStoreYieldsNoSignal() {
        assertThat(provider.candidates(null, "Preload", Map.of(), 50)).isEmpty();
    }

    @Test
    void storeWithoutCandidateSearchYieldsNoSignal() {
        PxChunkDao dao = mock(PxChunkDao.class);
        when(dao.searchCandidates("Preload", Map.of(), 50))
                .thenThrow(new UnsupportedOperationException("no analyzed index"));

        assertThat(provider.candidates(dao, "Preload", Map.of(), 50)).isEmpty();
    }
}
