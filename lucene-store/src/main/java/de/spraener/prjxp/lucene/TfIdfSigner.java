package de.spraener.prjxp.lucene;

import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import de.spraener.prjxp.common.model.PxChunk;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.Weight;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.FixedBitSet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic, LLM-free signatures for chunks and clusters (paper §3.3): the top terms of a
 * chunk are computed with TF-IDF, the top terms of a cluster with class-based TF-IDF. Both are
 * derived from the inverted index statistics of Apache Lucene only — no embeddings, no LLM.
 */
public class TfIdfSigner {

    /** topN = number of returned terms, minTermLength = shortest accepted term length. */
    public record SignerConfig(int topN, int minTermLength) {
        public static SignerConfig defaults() {
            return new SignerConfig(8, 3);
        }
    }

    private static final String CONTENT_FIELD = "content";

    private static final Set<String> STOPWORDS = new LinkedHashSet<>(List.of(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "at", "by", "for", "with", "from",
            "is", "are", "was", "were", "be", "been", "being", "this", "that", "these", "those", "it",
            "its", "as", "if", "then", "else", "when", "while", "per", "via", "not", "no", "yes",
            "der", "die", "das", "und", "oder", "von", "zu", "im", "an", "auf", "für", "mit", "bei",
            "nach", "über", "unter", "aus", "ist", "sind", "wird", "werden", "kann", "können", "muss",
            "müssen", "sich", "eine", "einen", "einem", "einer", "dem", "den", "des", "am", "um",
            "als", "auch", "nur", "wie", "was", "wer", "wo", "wann"));

    private static final Comparator<ScoredTerm> RANKING =
            Comparator.comparingDouble(ScoredTerm::score).reversed()
                    .thenComparing(ScoredTerm::term);

    private record ScoredTerm(String term, double score) {}

    /** Aggregated index statistics of one project: per-chunk term frequencies and document frequencies. */
    private static final class IndexScan {
        private final Map<String, Map<String, Integer>> tfByChunk = new HashMap<>();
        private final Map<String, Integer> dfByTerm = new HashMap<>();
        private int documentCount;
    }

    private final SignerConfig config;

    public TfIdfSigner(SignerConfig config) {
        this.config = config == null ? SignerConfig.defaults() : config;
    }

    /**
     * Top TF-IDF terms per logical chunk (key = pxchunk_id) of one project. Several Lucene
     * documents of the same chunk (parts) are aggregated into one chunk (frequencies summed).
     */
    public Map<String, List<String>> signChunks(LuceneEmbeddingStore store, String project) throws IOException {
        IndexScan scan = scan(store, project);
        Map<String, List<String>> signatures = new TreeMap<>();
        for (Map.Entry<String, Map<String, Integer>> chunkEntry : scan.tfByChunk.entrySet()) {
            List<ScoredTerm> scored = new ArrayList<>(chunkEntry.getValue().size());
            for (Map.Entry<String, Integer> termFrequency : chunkEntry.getValue().entrySet()) {
                scored.add(new ScoredTerm(termFrequency.getKey(),
                        tfIdf(termFrequency.getValue(), scan.documentCount,
                                scan.dfByTerm.getOrDefault(termFrequency.getKey(), 0))));
            }
            signatures.put(chunkEntry.getKey(), topTerms(scored));
        }
        return signatures;
    }

    /**
     * Top c-TF-IDF terms per cluster. {@code clusterToChunkIds} maps a cluster id to the
     * pxchunk_ids of its members; a cluster is treated as one pseudo-document
     * (frequencies summed over its members).
     */
    public Map<String, List<String>> signClusters(LuceneEmbeddingStore store, String project,
                                                  Map<String, List<String>> clusterToChunkIds) throws IOException {
        Map<String, List<String>> signatures = new LinkedHashMap<>();
        if (clusterToChunkIds == null || clusterToChunkIds.isEmpty()) {
            return signatures;
        }

        IndexScan scan = scan(store, project);

        Map<String, Map<String, Integer>> tfByCluster = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> cluster : clusterToChunkIds.entrySet()) {
            Map<String, Integer> clusterTerms = new HashMap<>();
            if (cluster.getValue() != null) {
                for (String chunkId : cluster.getValue()) {
                    Map<String, Integer> chunkTerms = scan.tfByChunk.get(chunkId);
                    if (chunkTerms == null) {
                        continue;
                    }
                    chunkTerms.forEach((term, tf) -> clusterTerms.merge(term, tf, Integer::sum));
                }
            }
            tfByCluster.put(cluster.getKey(), clusterTerms);
        }

        int clusterCount = tfByCluster.size();
        Map<String, Integer> documentFrequencyByCluster = new HashMap<>();
        for (Map<String, Integer> clusterTerms : tfByCluster.values()) {
            for (String term : clusterTerms.keySet()) {
                documentFrequencyByCluster.merge(term, 1, Integer::sum);
            }
        }

        for (Map.Entry<String, Map<String, Integer>> cluster : tfByCluster.entrySet()) {
            List<ScoredTerm> scored = new ArrayList<>(cluster.getValue().size());
            for (Map.Entry<String, Integer> termFrequency : cluster.getValue().entrySet()) {
                scored.add(new ScoredTerm(termFrequency.getKey(),
                        tfIdf(termFrequency.getValue(), clusterCount,
                                documentFrequencyByCluster.getOrDefault(termFrequency.getKey(), 0))));
            }
            signatures.put(cluster.getKey(), topTerms(scored));
        }
        return signatures;
    }

    /**
     * Paper formula: {@code score(t, d) = tf(t,d) * (1 + log((N + 1) / (df(t) + 1)))}.
     */
    private static double tfIdf(int termFrequency, int documentCount, int documentFrequency) {
        return termFrequency * (1.0 + Math.log((documentCount + 1.0) / (documentFrequency + 1.0)));
    }

    private List<String> topTerms(List<ScoredTerm> scored) {
        int limit = Math.max(0, config.topN());
        if (limit == 0 || scored.isEmpty()) {
            return List.of();
        }
        scored.sort(RANKING);
        List<String> terms = new ArrayList<>(Math.min(limit, scored.size()));
        for (int i = 0; i < scored.size() && i < limit; i++) {
            terms.add(scored.get(i).term());
        }
        return terms;
    }

    /**
     * Single pass over all leaves of the index, restricted to the live documents of one project.
     * Only the analysed {@code content} field is evaluated (metadata fields are StringFields).
     */
    private IndexScan scan(LuceneEmbeddingStore store, String project) throws IOException {
        IndexScan scan = new IndexScan();
        IndexSearcher searcher = store.getSearcher();
        try {
            Query projectQuery = LuceneFilterConverter.convert(
                    new IsEqualTo(PxChunk.PXCHUNK_PROJECT, project));
            Weight weight = searcher.createWeight(
                    searcher.rewrite(projectQuery), ScoreMode.COMPLETE_NO_SCORES, 1f);
            StoredFields storedFields = searcher.storedFields();

            for (LeafReaderContext leafContext : searcher.getIndexReader().leaves()) {
                LeafReader leaf = leafContext.reader();
                FixedBitSet matches = FilterDocs.matchingDocs(weight, leafContext);
                int matchedDocs = matches.cardinality();
                if (matchedDocs == 0) {
                    continue;
                }
                scan.documentCount += matchedDocs;

                Terms terms = leaf.terms(CONTENT_FIELD);
                if (terms == null) {
                    continue;
                }

                String[] chunkIdByDoc = new String[leaf.maxDoc()];
                TermsEnum termsEnum = terms.iterator();
                PostingsEnum postings = null;
                for (BytesRef term = termsEnum.next(); term != null; term = termsEnum.next()) {
                    String termText = term.utf8ToString();
                    if (!isSignificant(termText)) {
                        continue;
                    }
                    postings = termsEnum.postings(postings, PostingsEnum.FREQS);
                    int documentFrequency = 0;
                    for (int docId = postings.nextDoc();
                         docId != DocIdSetIterator.NO_MORE_DOCS;
                         docId = postings.nextDoc()) {
                        if (!matches.get(docId)) {
                            continue;
                        }
                        String chunkId = chunkIdByDoc[docId];
                        if (chunkId == null) {
                            chunkId = storedFields.document(leafContext.docBase + docId)
                                    .get(PxChunk.PXCHUNK_ID);
                            if (chunkId == null) {
                                continue;
                            }
                            chunkIdByDoc[docId] = chunkId;
                        }
                        documentFrequency++;
                        scan.tfByChunk
                                .computeIfAbsent(chunkId, key -> new HashMap<>())
                                .merge(termText, postings.freq(), Integer::sum);
                    }
                    if (documentFrequency > 0) {
                        scan.dfByTerm.merge(termText, documentFrequency, Integer::sum);
                    }
                }
            }
            return scan;
        } finally {
            store.releaseSearcher(searcher);
        }
    }

    private boolean isSignificant(String term) {
        return term.length() >= config.minTermLength() && !STOPWORDS.contains(term);
    }
}
