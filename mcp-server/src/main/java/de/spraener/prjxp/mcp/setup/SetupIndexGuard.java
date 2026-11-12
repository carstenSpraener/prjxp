package de.spraener.prjxp.mcp.setup;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.model.EmbeddedChunkRecord;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.tibed.EmbeddingCompatibilityChecker;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class SetupIndexGuard {
    private final ConfigurationStore store;
    public SetupIndexGuard(ConfigurationStore store) { this.store = store; }

    public Validation verify(Map<String, Object> root, Map<String, Object> candidate, EmbeddingModel model) {
        State state = inspect(root);
        if (state.samples().isEmpty()) return new Validation(state.fingerprint(), candidate, 0, 1);
        HubProperties hub = new HubProperties();
        float low = store.bindOrDefault(root, "prjxp.hub.compatibility-threshold-low", Float.class, hub.getCompatibilityThresholdLow());
        float high = store.bindOrDefault(root, "prjxp.hub.compatibility-threshold-high", Float.class, hub.getCompatibilityThresholdHigh());
        if (!Float.isFinite(low) || !Float.isFinite(high) || low < 0 || low > high || high > 1) throw SetupData.invalid();
        EmbeddingCompatibilityChecker.CheckReport report;
        try {
            report = new EmbeddingCompatibilityChecker().check(state.samples(), model,
                    state.samples().size(), high, low);
        } catch (IllegalArgumentException error) { throw incompatible(); }
        if (report.samples() != state.samples().size() || !Float.isFinite(report.avgCosine())
                || report.verdict() == EmbeddingCompatibilityChecker.Verdict.INCOMPATIBLE) throw incompatible();
        return new Validation(state.fingerprint(), candidate, report.samples(), report.avgCosine());
    }

    public void require(Map<String, Object> root, Map<String, Object> candidate, Map<String, Object> proof) {
        State state = inspect(root);
        if (state.samples().isEmpty()) return;
        if (!(proof.get("dimensions") instanceof Number measured) || measured.intValue() != state.dimension()
                || !state.fingerprint().equals(proof.get("index-fingerprint"))) throw incompatible();
    }

    Validation emptyValidation(Map<String, Object> root, Map<String, Object> candidate) {
        State state = inspect(root);
        if (!state.samples().isEmpty()) throw incompatible();
        return new Validation(state.fingerprint(), candidate, 0, 1);
    }

    void requireValidation(Map<String, Object> root, Map<String, Object> candidate, Validation validation) {
        if (validation == null || !Arrays.equals(SetupData.canonical(candidate), validation.candidate)
                || !inspect(root).fingerprint().equals(validation.fingerprint())) throw incompatible();
    }

    private State inspect(Map<String, Object> root) {
        var config = store.bind(root, "prjxp.embedding-store-lucene", PrjXPConfig.LuceneEmbeddingStoreConfig.class);
        Path path = Path.of(config.getIndexPath()).toAbsolutePath().normalize();
        List<EmbeddedChunkRecord> samples = new ArrayList<>();
        int dimension = 0;
        int count = store.bindOrDefault(root, "prjxp.hub.compatibility-sample-size", Integer.class,
                new HubProperties().getCompatibilitySampleSize());
        if (count <= 0) throw SetupData.invalid();
        try {
            if (Files.isDirectory(path)) {
                try (var directory = FSDirectory.open(path)) {
                    if (DirectoryReader.indexExists(directory)) {
                        try (var reader = DirectoryReader.open(directory)) {
                            for (var leaf : reader.leaves()) {
                                var vectors = leaf.reader().getFloatVectorValues("vector");
                                if (vectors == null) continue;
                                if (dimension != 0 && dimension != vectors.dimension()) throw incompatible();
                                dimension = vectors.dimension();
                                int doc;
                                while (samples.size() < count && (doc = vectors.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
                                    var live = leaf.reader().getLiveDocs();
                                    if (live != null && !live.get(doc)) continue;
                                    var stored = leaf.reader().storedFields().document(doc);
                                    String id = stored.get("_id"), content = stored.get("content");
                                    if (id == null || content == null || content.isBlank()) throw incompatible();
                                    float[] vector = vectors.vectorValue().clone();
                                    PxChunk chunk = PxChunk.fromContentAndMap(content, Map.of(PxChunk.PXCHUNK_ID, id));
                                    samples.add(EmbeddedChunkRecord.from(chunk, vector));
                                }
                            }
                            if (reader.numDocs() > 0 && samples.isEmpty()) throw incompatible();
                        }
                    }
                }
            } else if (Files.exists(path)) throw incompatible();
        } catch (IOException error) {
            throw new SetupException("CONFIG_READ_FAILED", "embedding", 500, "Cannot inspect embedding index");
        }
        List<Object> material = new ArrayList<>();
        material.add(path.toString());
        for (EmbeddedChunkRecord sample : samples) {
            List<Float> vector = new ArrayList<>();
            for (float value : sample.vector()) {
                if (!Float.isFinite(value)) throw incompatible();
                vector.add(value);
            }
            material.add(List.of(sample.id(), sample.content(), vector));
        }
        String fingerprint = Base64.getEncoder().encodeToString(SetupTestEvidenceService.digest(
                new byte[0], SetupData.canonical(material)));
        return new State(fingerprint, dimension, samples);
    }

    private static SetupException incompatible() {
        return new SetupException("INDEX_REBUILD_REQUIRED", "embedding", 409,
                "The index requires a successful vector compatibility test or explicit reindexing");
    }

    private record State(String fingerprint, int dimension, List<EmbeddedChunkRecord> samples) {}
    public static final class Validation {
        private final String fingerprint;
        private final byte[] candidate;
        private final int samples;
        private final float cosine;
        private Validation(String fingerprint, Map<String, Object> candidate, int samples, float cosine) {
            this.fingerprint = fingerprint; this.candidate = SetupData.canonical(candidate);
            this.samples = samples; this.cosine = cosine;
        }
        public String fingerprint() { return fingerprint; }
        public int samples() { return samples; }
        public float cosine() { return cosine; }
    }
}
