package de.spraener.prjxp.common.store;

import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PxChunkDaoTest {

    private final PxChunkDao dao = new PxChunkDao() {
        @Override public PrjXPEmbeddingStoreReference getStoreReference() { return null; }
        @Override public List<PxChunk> findById(String id) { return Collections.emptyList(); }
        @Override public List<PxChunk> findByMetaData(Map<String, String> metaData) { return Collections.emptyList(); }
        @Override public List<PxChunk> findRelevant(String question, int maxResults, double minScore) { return Collections.emptyList(); }
        @Override public Stream<PxChunk> findAll() { return Stream.empty(); }
    };

    @Test
    void searchFullText_throwsUnsupportedOperationException() {
        assertThatThrownBy(() -> dao.searchFullText("query", Map.of(), 10))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Full-text");
    }

    @Test
    void searchByIndex_throwsUnsupportedOperationException() {
        assertThatThrownBy(() -> dao.searchByIndex(Map.of(), 10))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Index");
    }

    @Test
    void searchVector_throwsUnsupportedOperationException() {
        assertThatThrownBy(() -> dao.searchVector("query", Map.of(), 10))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Vector");
    }
}
