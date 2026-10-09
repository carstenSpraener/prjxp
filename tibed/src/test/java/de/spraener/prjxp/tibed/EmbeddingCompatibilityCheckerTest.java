package de.spraener.prjxp.tibed;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.model.EmbeddedChunkRecord;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingCompatibilityCheckerTest {

    private final ObjectMapper objMapper = new ObjectMapper();
    private final EmbeddingCompatibilityChecker checker = new EmbeddingCompatibilityChecker();

    @Test
    void identicalVectorsAreCompatible() {
        float[] vector = new float[]{0.1f, 0.2f, 0.3f};
        EmbeddedChunkRecord record = createRecord("c1", "hello", vector);

        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(any(TextSegment.class))).thenReturn(new Response<>(Embedding.from(vector)));

        EmbeddingCompatibilityChecker.CheckReport report = checker.check(List.of(record), model, 1, 0.995f, 0.975f);
        assertThat(report.verdict()).isEqualTo(EmbeddingCompatibilityChecker.Verdict.COMPATIBLE);
        assertThat(report.avgCosine()).isCloseTo(1.0f, offset(0.001f));
    }

    @Test
    void slightlyDifferentVectorsArePartiallyCompatible() {
        float[] imported = new float[]{1.0f, 0.0f};
        float[] local = new float[]{0.98f, 0.2f};
        EmbeddedChunkRecord record = createRecord("c1", "hello", imported);

        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(any(TextSegment.class))).thenReturn(new Response<>(Embedding.from(local)));

        EmbeddingCompatibilityChecker.CheckReport report = checker.check(List.of(record), model, 1, 0.995f, 0.975f);
        assertThat(report.verdict()).isEqualTo(EmbeddingCompatibilityChecker.Verdict.PARTIALLY_COMPATIBLE);
        assertThat(report.avgCosine()).isGreaterThan(0.975f).isLessThan(0.995f);
    }

    @Test
    void veryDifferentVectorsAreIncompatible() {
        float[] imported = new float[]{1.0f, 0.0f};
        float[] local = new float[]{0.0f, 1.0f};
        EmbeddedChunkRecord record = createRecord("c1", "hello", imported);

        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(any(TextSegment.class))).thenReturn(new Response<>(Embedding.from(local)));

        EmbeddingCompatibilityChecker.CheckReport report = checker.check(List.of(record), model, 1, 0.995f, 0.975f);
        assertThat(report.verdict()).isEqualTo(EmbeddingCompatibilityChecker.Verdict.INCOMPATIBLE);
    }

    @Test
    void emptyListReturnsIncompatible() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        EmbeddingCompatibilityChecker.CheckReport report = checker.check(List.of(), model, 4, 0.995f, 0.975f);
        assertThat(report.verdict()).isEqualTo(EmbeddingCompatibilityChecker.Verdict.INCOMPATIBLE);
        assertThat(report.samples()).isZero();
    }

    @Test
    void nullListReturnsIncompatible() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        EmbeddingCompatibilityChecker.CheckReport report = checker.check(null, model, 4, 0.995f, 0.975f);
        assertThat(report.verdict()).isEqualTo(EmbeddingCompatibilityChecker.Verdict.INCOMPATIBLE);
    }

    @Test
    void cosineSimilarityOfIdenticalVectorsIsOne() {
        float[] a = new float[]{0.1f, 0.2f, 0.3f};
        float[] b = new float[]{0.1f, 0.2f, 0.3f};
        assertThat(EmbeddingCompatibilityChecker.cosineSimilarity(a, b)).isCloseTo(1.0f, offset(0.001f));
    }

    @Test
    void cosineSimilarityOfOrthogonalVectorsIsZero() {
        float[] a = new float[]{1.0f, 0.0f};
        float[] b = new float[]{0.0f, 1.0f};
        assertThat(EmbeddingCompatibilityChecker.cosineSimilarity(a, b)).isCloseTo(0.0f, offset(0.001f));
    }

    @Test
    void cosineSimilarityOfOppositeVectorsIsMinusOne() {
        float[] a = new float[]{1.0f, 0.0f};
        float[] b = new float[]{-1.0f, 0.0f};
        assertThat(EmbeddingCompatibilityChecker.cosineSimilarity(a, b)).isCloseTo(-1.0f, offset(0.001f));
    }

    @Test
    void cosineSimilarityThrowsOnDimensionMismatch() {
        float[] a = new float[]{1.0f, 2.0f};
        float[] b = new float[]{1.0f};
        try {
            EmbeddingCompatibilityChecker.cosineSimilarity(a, b);
            org.junit.jupiter.api.Assertions.fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage()).contains("Vector dimension mismatch");
        }
    }

    @Test
    void cosineSimilarityOfZeroVectorsReturnsZero() {
        float[] a = new float[]{0.0f, 0.0f};
        float[] b = new float[]{0.0f, 0.0f};
        assertThat(EmbeddingCompatibilityChecker.cosineSimilarity(a, b)).isZero();
    }

    @Test
    void checkReportToStringIsReadable() {
        EmbeddingCompatibilityChecker.CheckReport report = new EmbeddingCompatibilityChecker.CheckReport(0.98f, 4, EmbeddingCompatibilityChecker.Verdict.PARTIALLY_COMPATIBLE);
        assertThat(report.toString()).contains("98");   // locale-independent: may be "0.98" or "0,98"
        assertThat(report.toString()).contains("4");
        assertThat(report.toString()).contains("PARTIALLY_COMPATIBLE");
    }

    private EmbeddedChunkRecord createRecord(String id, String content, float[] vector) {
        return new EmbeddedChunkRecord(id, "text/plain", "/test.java", null, 0, 1,
                "1", "10", content.length(), 0, Map.of(), content, vector);
    }
}
