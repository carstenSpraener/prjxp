package de.spraener.prjxp.common.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.java.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Decorator that caps the number of texts sent to the underlying provider in a single
 * HTTP request.
 * <p>
 * langchain4j's models differ in how they batch: {@code OpenAiEmbeddingModel} splits
 * {@code embedAll()} into requests of at most {@code maxSegmentsPerBatch} (default 2048),
 * while {@code OllamaEmbeddingModel} and the Gemini model send the whole list in one
 * request. This decorator applies the same cap uniformly, so every model-creation path
 * behaves identically regardless of the concrete provider.
 * <p>
 * The cap is the per-HTTP-request limit ({@code prjxp.embedding.maxSegmentsPerBatch});
 * it is independent of the per-project pipeline batch size ({@code tibedBatchSize}).
 */
@Log
public class CappedEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel delegate;
    private final int maxSegmentsPerBatch;

    public CappedEmbeddingModel(EmbeddingModel delegate, int maxSegmentsPerBatch) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (maxSegmentsPerBatch < 1) {
            throw new IllegalArgumentException("maxSegmentsPerBatch must be >= 1, got " + maxSegmentsPerBatch);
        }
        this.delegate = delegate;
        this.maxSegmentsPerBatch = maxSegmentsPerBatch;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        // Small or empty batches go straight to the delegate without any copying.
        if (textSegments == null || textSegments.isEmpty() || textSegments.size() <= maxSegmentsPerBatch) {
            return delegate.embedAll(textSegments);
        }

        int size = textSegments.size();
        int requests = (size + maxSegmentsPerBatch - 1) / maxSegmentsPerBatch;
        log.info("Splitting " + size + " embedding segments into " + requests + " provider requests (cap=" + maxSegmentsPerBatch + ")");

        List<Embedding> all = new ArrayList<>(size);
        TokenUsage total = null;
        FinishReason lastFinishReason = null;
        for (int i = 0; i < size; i += maxSegmentsPerBatch) {
            int end = Math.min(i + maxSegmentsPerBatch, size);
            Response<List<Embedding>> sub = delegate.embedAll(textSegments.subList(i, end));
            all.addAll(sub.content());
            total = TokenUsage.sum(total, sub.tokenUsage());
            if (sub.finishReason() != null) {
                lastFinishReason = sub.finishReason();
            }
        }
        return Response.from(all, total, lastFinishReason);
    }

    @Override
    public int dimension() {
        return delegate.dimension();
    }

    @Override
    public String modelName() {
        return delegate.modelName();
    }

    public EmbeddingModel delegate() {
        return delegate;
    }

    public int maxSegmentsPerBatch() {
        return maxSegmentsPerBatch;
    }
}
