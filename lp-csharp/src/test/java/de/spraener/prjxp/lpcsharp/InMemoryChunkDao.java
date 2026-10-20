package de.spraener.prjxp.lpcsharp;

import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.model.FileListResult;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.store.PxChunkDao;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A tiny in-memory {@link PxChunkDao} for retriever tests: supports
 * {@code findById} (all parts of a chunk) and {@code findByMetaData}.
 */
class InMemoryChunkDao implements PxChunkDao {

    private final PrjXPEmbeddingStoreReference reference;
    private final Map<String, List<PxChunk>> byId = new HashMap<>();
    private final List<PxChunk> all = new ArrayList<>();

    InMemoryChunkDao(String projectName) {
        PrjXPEmbeddingStoreReference ref = new PrjXPEmbeddingStoreReference();
        ref.setProjectName(projectName);
        this.reference = ref;
    }

    void add(PxChunk chunk) {
        all.add(chunk);
        byId.computeIfAbsent(chunk.getId(), k -> new ArrayList<>()).add(chunk);
    }

    @Override
    public PrjXPEmbeddingStoreReference getStoreReference() {
        return reference;
    }

    @Override
    public List<PxChunk> findById(String id) {
        return byId.getOrDefault(id, List.of());
    }

    @Override
    public List<PxChunk> findByMetaData(Map<String, String> metaData) {
        List<PxChunk> result = new ArrayList<>();
        for (PxChunk c : all) {
            boolean match = true;
            for (Map.Entry<String, String> e : metaData.entrySet()) {
                if (!e.getValue().equals(c.getMetadata().get(e.getKey()))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                result.add(c);
            }
        }
        return result;
    }

    @Override
    public List<PxChunk> findRelevant(String question, int maxResults, double minScore) {
        return List.of();
    }

    @Override
    public Stream<PxChunk> findAll() {
        return all.stream();
    }

    @Override
    public List<ScoredChunk> searchByIndex(Map<String, String> filters, int limit) {
        return List.of();
    }

    @Override
    public List<ScoredChunk> searchVector(String query, Map<String, String> filters, int limit) {
        return List.of();
    }

    @Override
    public FileListResult listFiles(String pattern, int limit) {
        return new FileListResult(List.of(), false);
    }
}
