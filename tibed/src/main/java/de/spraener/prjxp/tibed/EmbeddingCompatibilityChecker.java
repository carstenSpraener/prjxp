package de.spraener.prjxp.tibed;

import de.spraener.prjxp.common.model.EmbeddedChunkRecord;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates that pre-embedded vectors from an external source are compatible with the local embedding model.
 * Cross-platform rounding differences (e.g. Apple Silicon GPU vs AMD64 CPU) can cause systematic COSINE
 * distance shifts, leading to poor search quality. This checker embeds a sample of chunks locally and compares
 * them against the imported vectors to detect incompatibilities before import.
 */
@Component
@RequiredArgsConstructor
public class EmbeddingCompatibilityChecker {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingCompatibilityChecker.class);

    public enum Verdict {
        /** Vectors are practically identical (same hardware, same precision). */
        COMPATIBLE,
        /** Vectors show acceptable deviation (same model, different hardware). Import proceeds with warning. */
        PARTIALLY_COMPATIBLE,
        /** Vectors are too different (different model or incompatible precision). Import is blocked. */
        INCOMPATIBLE
    }

    public record CheckReport(float avgCosine, int samples, Verdict verdict) {
        @Override
        public String toString() {
            return "CheckReport{avgCosine=%.4f, samples=%d, verdict=%s}".formatted(avgCosine, samples, verdict);
        }
    }

    /** Default threshold for COMPATIBLE (practically identical vectors). */
    private static final float DEFAULT_THRESHOLD_HIGH = 0.995f;
    /** Default threshold for PARTIALLY_COMPATIBLE (acceptable cross-platform deviation). */
    private static final float DEFAULT_THRESHOLD_LOW = 0.975f;

    /**
     * Checks embedding compatibility by comparing imported vectors against locally computed ones.
     *
     * @param records       pre-embedded chunk records from the JSONL file (first N are sampled)
     * @param embeddingModel local embedding model used for comparison
     * @param sampleSize    number of chunks to compare (default 4)
     * @param thresholdHigh minimum avg COSINE for COMPATIBLE verdict (default 0.995)
     * @param thresholdLow  minimum avg COSINE for PARTIALLY_COMPATIBLE verdict (default 0.975)
     * @return report with average COSINE similarity, sample count and verdict
     */
    public CheckReport check(List<EmbeddedChunkRecord> records, EmbeddingModel embeddingModel,
                              int sampleSize, float thresholdHigh, float thresholdLow) {
        if (records == null || records.isEmpty()) {
            CheckReport report = new CheckReport(0f, 0, Verdict.INCOMPATIBLE);
            log.warn("No embedded chunk records available for compatibility check — marking INCOMPATIBLE");
            return report;
        }

        int actualSamples = Math.min(sampleSize, records.size());
        List<Float> similarities = new ArrayList<>(actualSamples);

        for (int i = 0; i < actualSamples; i++) {
            EmbeddedChunkRecord record = records.get(i);
            float[] importedVector = record.vector();

            if (importedVector == null || importedVector.length == 0) {
                log.warn("Chunk {} has no vector — skipping", record.id());
                continue;
            }

            Embedding localEmbedding = embeddingModel.embed(TextSegment.from(record.content())).content();
            float similarity = cosineSimilarity(importedVector, localEmbedding.vector());
            similarities.add(similarity);
        }

        if (similarities.isEmpty()) {
            CheckReport report = new CheckReport(0f, actualSamples, Verdict.INCOMPATIBLE);
            log.warn("No valid vectors found in {} samples — marking INCOMPATIBLE", actualSamples);
            return report;
        }

        float avgCosine = (float) similarities.stream().mapToDouble(f -> f).average().orElse(0f);
        Verdict verdict = determineVerdict(avgCosine, thresholdHigh, thresholdLow);

        CheckReport report = new CheckReport(avgCosine, similarities.size(), verdict);
        log.info("Embedding compatibility check: {} (sampled {} of {} records)", report, similarities.size(), records.size());
        return report;
    }

    /**
     * Convenience overload using default thresholds.
     */
    public CheckReport check(List<EmbeddedChunkRecord> records, EmbeddingModel embeddingModel, int sampleSize) {
        return check(records, embeddingModel, sampleSize, DEFAULT_THRESHOLD_HIGH, DEFAULT_THRESHOLD_LOW);
    }

    private Verdict determineVerdict(float avgCosine, float thresholdHigh, float thresholdLow) {
        if (avgCosine >= thresholdHigh) {
            return Verdict.COMPATIBLE;
        } else if (avgCosine >= thresholdLow) {
            return Verdict.PARTIALLY_COMPATIBLE;
        } else {
            return Verdict.INCOMPATIBLE;
        }
    }

    /**
     * Computes COSINE similarity between two float vectors of equal length.
     */
    static float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector dimension mismatch: " + a.length + " vs " + b.length);
        }

        float dotProduct = 0f;
        float normA = 0f;
        float normB = 0f;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        float denominator = (float) Math.sqrt(normA) * (float) Math.sqrt(normB);
        if (denominator == 0f) {
            return 0f;   // zero vector(s) — no meaningful similarity
        }

        return dotProduct / denominator;
    }
}
