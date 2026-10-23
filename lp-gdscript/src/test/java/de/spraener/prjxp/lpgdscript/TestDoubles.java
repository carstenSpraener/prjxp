package de.spraener.prjxp.lpgdscript;

import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * In-memory {@link PxChunkDao} for retriever tests. Only {@code findById} is
 * backed by data; all other queries return empty results.
 */
class InMemoryChunkDao implements PxChunkDao {
    private final Map<String, List<PxChunk>> byId = new HashMap<>();

    void put(String id, PxChunk... chunks) {
        byId.computeIfAbsent(id, k -> new ArrayList<>()).addAll(List.of(chunks));
    }

    @Override
    public PrjXPEmbeddingStoreReference getStoreReference() {
        return null;
    }

    @Override
    public List<PxChunk> findById(String id) {
        return byId.getOrDefault(id, List.of());
    }

    @Override
    public List<PxChunk> findByMetaData(Map<String, String> m) {
        return List.of();
    }

    @Override
    public List<PxChunk> findRelevant(String q, int max, double min) {
        return List.of();
    }

    @Override
    public Stream<PxChunk> findAll() {
        return Stream.empty();
    }
}

/**
 * Test {@link PxChunkDaoProvider} that bypasses store-reference matching and
 * returns the injected dao for every project (the lp-gdscript test classpath
 * has no Mockito, so the concrete provider is subclassed instead).
 */
class TestChunkDaoProvider extends PxChunkDaoProvider {
    private final PxChunkDao delegate;

    TestChunkDaoProvider(PxChunkDao delegate) {
        super(List.of());
        this.delegate = delegate;
    }

    @Override
    public Optional<PxChunkDao> get(String prjName) {
        return Optional.ofNullable(delegate);
    }
}
