package de.spraener.prjxp.common.embedding;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CappedEmbeddingModelTest {

    /**
     * Fake model that records the size of every list it receives and encodes the
     * segment's original index (carried in its text) into the returned vector, so the
     * test can verify that embeddings come back in the original order.
     */
    private static class RecordingModel implements EmbeddingModel {
        final List<Integer> receivedSizes = new ArrayList<>();
        private final int dimension;
        private final String modelName;
        private final TokenUsage tokenUsage;
        private final FinishReason finishReason;

        RecordingModel(int dimension, String modelName, TokenUsage tokenUsage, FinishReason finishReason) {
            this.dimension = dimension;
            this.modelName = modelName;
            this.tokenUsage = tokenUsage;
            this.finishReason = finishReason;
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
            receivedSizes.add(textSegments.size());
            List<Embedding> embeddings = new ArrayList<>(textSegments.size());
            for (TextSegment segment : textSegments) {
                int originalIndex = Integer.parseInt(segment.text());
                embeddings.add(new Embedding(new float[]{originalIndex}));
            }
            return Response.from(embeddings, tokenUsage, finishReason);
        }

        @Override
        public int dimension() {
            return dimension;
        }

        @Override
        public String modelName() {
            return modelName;
        }
    }

    private static List<TextSegment> segments(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> TextSegment.from(String.valueOf(i)))
                .toList();
    }

    @Test
    void fiveSegments_capTwo_splitsInto221_andPreservesOrder() {
        RecordingModel fake = new RecordingModel(8, "m", null, null);
        CappedEmbeddingModel model = new CappedEmbeddingModel(fake, 2);

        Response<List<Embedding>> response = model.embedAll(segments(5));

        assertThat(fake.receivedSizes).containsExactly(2, 2, 1);
        assertThat(response.content()).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(response.content().get(i).vector()).containsExactly((float) i);
        }
    }

    @Test
    void listSizeEqualsCap_singleDelegateCall() {
        RecordingModel fake = new RecordingModel(8, "m", null, null);
        CappedEmbeddingModel model = new CappedEmbeddingModel(fake, 3);

        model.embedAll(segments(3));

        assertThat(fake.receivedSizes).containsExactly(3);
    }

    @Test
    void listSizeBelowCap_singleDelegateCall() {
        RecordingModel fake = new RecordingModel(8, "m", null, null);
        CappedEmbeddingModel model = new CappedEmbeddingModel(fake, 5);

        model.embedAll(segments(3));

        assertThat(fake.receivedSizes).containsExactly(3);
    }

    @Test
    void emptyList_singleDelegateCallWithEmptyList() {
        RecordingModel fake = new RecordingModel(8, "m", null, null);
        CappedEmbeddingModel model = new CappedEmbeddingModel(fake, 2);

        Response<List<Embedding>> response = model.embedAll(List.of());

        assertThat(fake.receivedSizes).containsExactly(0);
        assertThat(response.content()).isEmpty();
    }

    @Test
    void dimensionAndModelName_delegate() {
        RecordingModel fake = new RecordingModel(1024, "mxbai-embed-large", null, null);
        CappedEmbeddingModel model = new CappedEmbeddingModel(fake, 32);

        assertThat(model.dimension()).isEqualTo(1024);
        assertThat(model.modelName()).isEqualTo("mxbai-embed-large");
    }

    @Test
    void tokenUsage_isSummedAcrossSubCalls() {
        RecordingModel fake = new RecordingModel(8, "m", new TokenUsage(10, 5), null);
        CappedEmbeddingModel model = new CappedEmbeddingModel(fake, 2);

        Response<List<Embedding>> response = model.embedAll(segments(5)); // 3 sub-calls

        assertThat(fake.receivedSizes).containsExactly(2, 2, 1);
        assertThat(response.tokenUsage().inputTokenCount()).isEqualTo(30);
        assertThat(response.tokenUsage().outputTokenCount()).isEqualTo(15);
        assertThat(response.tokenUsage().totalTokenCount()).isEqualTo(45);
    }

    @Test
    void tokenUsage_nullInDelegate_staysNull() {
        RecordingModel fake = new RecordingModel(8, "m", null, null);
        CappedEmbeddingModel model = new CappedEmbeddingModel(fake, 2);

        Response<List<Embedding>> response = model.embedAll(segments(5));

        assertThat(response.tokenUsage()).isNull();
    }

    @Test
    void finishReason_lastNonNullIsPropagated() {
        RecordingModel fake = new RecordingModel(8, "m", null, FinishReason.STOP);
        CappedEmbeddingModel model = new CappedEmbeddingModel(fake, 2);

        Response<List<Embedding>> response = model.embedAll(segments(5));

        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
    }

    @Test
    void accessors_exposeDelegateAndCap() {
        RecordingModel fake = new RecordingModel(8, "m", null, null);
        CappedEmbeddingModel model = new CappedEmbeddingModel(fake, 32);

        assertThat(model.delegate()).isSameAs(fake);
        assertThat(model.maxSegmentsPerBatch()).isEqualTo(32);
    }

    @Test
    void capBelowOne_throwsIllegalArgumentException() {
        RecordingModel fake = new RecordingModel(8, "m", null, null);

        assertThatThrownBy(() -> new CappedEmbeddingModel(fake, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullDelegate_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> new CappedEmbeddingModel(null, 32))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splitLogsOneLinePerSplitWithExactNumbers() {
        RecordingModel fake = new RecordingModel(8, "m", null, null);
        CappedEmbeddingModel model = new CappedEmbeddingModel(fake, 2);

        Logger logger = Logger.getLogger(CappedEmbeddingModel.class.getName());
        Level originalLevel = logger.getLevel();
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void close() {
            }

            @Override
            public void flush() {
            }
        };
        logger.setLevel(Level.INFO);
        logger.addHandler(handler);
        try {
            model.embedAll(segments(5));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(originalLevel);
        }

        assertThat(messages).hasSize(1);
        assertThat(messages).allSatisfy(m -> assertThat(m).contains("Splitting 5 embedding segments into 3 provider requests (cap=2)"));
    }

    @Test
    void noSplitLogWhenBatchFitsTheCap() {
        RecordingModel fake = new RecordingModel(8, "m", null, null);
        CappedEmbeddingModel model = new CappedEmbeddingModel(fake, 5);

        Logger logger = Logger.getLogger(CappedEmbeddingModel.class.getName());
        Level originalLevel = logger.getLevel();
        List<String> messages = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void close() {
            }

            @Override
            public void flush() {
            }
        };
        logger.setLevel(Level.INFO);
        logger.addHandler(handler);
        try {
            model.embedAll(segments(3));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(originalLevel);
        }

        assertThat(messages).isEmpty();
    }
}
