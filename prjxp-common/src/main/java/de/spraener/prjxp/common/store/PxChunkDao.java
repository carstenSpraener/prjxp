package de.spraener.prjxp.common.store;

import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;import de.spraener.prjxp.common.model.FileListResult;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public interface PxChunkDao {
    PrjXPEmbeddingStoreReference  getStoreReference();
    List<PxChunk> findById(String id);
    List<PxChunk> findByMetaData(Map<String, String> metaData);
    List<PxChunk> findRelevant(String question, int maxResults, double minScore);
    Stream<PxChunk> findAll();

    default List<ScoredChunk> searchFullText(String query, Map<String, String> filters, int limit) {
        throw new UnsupportedOperationException("Full-text search is not supported by this store");
    }

    /**
     * Candidate generation for ranked search: like {@link #searchFullText}, but the hit list is a
     * superset — prefix and mid-word matches of the query tokens are included, so that a later
     * exact/whole-word check can decide. Scored by BM25.
     * <p>
     * {@code limit <= 0} asks for every match (bounded by the store's internal cap): a caller that
     * must be complete — {@code grep} answering "does this occur?" — cannot use a ranked window.
     * <p>
     * Default: the plain full-text search, so stores without an analyzed index keep working.
     */
    default List<ScoredChunk> searchCandidates(String query, Map<String, String> filters, int limit) {
        return searchFullText(query, filters, limit);
    }

    /**
     * OR search over the given terms: a chunk matches when its content matches <em>at least one</em>
     * term (prefix, then mid-word) — the opposite of {@link #searchCandidates}, which requires all
     * tokens. The keyword fallback (Phase 03) uses this: the keywords of a question are
     * alternatives, not a conjunction.
     * <p>
     * {@code limit <= 0} asks for every match (bounded by the store's internal cap).
     * Blank terms are ignored; an empty term list yields an empty result.
     */
    default List<ScoredChunk> searchAnyTerms(List<String> terms, Map<String, String> filters, int limit) {
        throw new UnsupportedOperationException("Any-term search is not supported by this store");
    }

    /**
     * Files whose name or path contains {@code token} (case-insensitive), file-name matches first,
     * then path matches, each group alphabetically. Cheap structural lookup for the name prior —
     * it answers "which files are about this word?", not "what do they say?".
     */
    default List<String> findFilesContaining(String token, int limit) {
        throw new UnsupportedOperationException("File name lookup is not supported by this store");
    }

    /**
     * Deterministic lookup on exact index fields (e.g. symbol metadata).
     * Filters use logical metadata keys, see {@link PxChunk#metadataFieldKey(String)}.
     */
    default List<ScoredChunk> searchByIndex(Map<String, String> filters, int limit) {
        throw new UnsupportedOperationException("Index search is not supported by this store");
    }

    /**
     * Semantic vector search, ranked by similarity.
     * Filters use logical metadata keys, see {@link PxChunk#metadataFieldKey(String)}.
     */
    default List<ScoredChunk> searchVector(String query, Map<String, String> filters, int limit) {
        throw new UnsupportedOperationException("Vector search is not supported by this store");
    }

    /**
     * Structural file listing: distinct file paths of this project's chunks matching a pattern
     * (prefix or glob; '*' spans path separators). A pattern ending in '/' lists directory
     * levels instead ('*&#47;' = top-level directories). Orthogonal to the content search
     * methods — it answers "which files exist?", not "what do they say?".
     */
    default FileListResult listFiles(String pattern, int limit) {
        throw new UnsupportedOperationException("File listing is not supported by this store");
    }
}
