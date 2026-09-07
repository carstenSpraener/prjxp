package de.spraener.prjxp.tibed;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministisches Embedding-Modell: Vektoren hängen nur von der Textlänge ab.
 */
class FakeEmbeddingModel implements EmbeddingModel {
    final int dimension;
    final List<List<TextSegment>> calls = new ArrayList<>();

    FakeEmbeddingModel(int dimension) {
        this.dimension = dimension;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        calls.add(segments);
        List<Embedding> embeddings = segments.stream()
                .map(segment -> {
                    float[] vector = new float[dimension];
                    int value = segment.text().length();
                    for (int i = 0; i < dimension; i++) {
                        vector[i] = value / 10f + i * 0.1f;
                    }
                    return Embedding.from(vector);
                })
                .toList();
        return new Response<>(embeddings);
    }
}
