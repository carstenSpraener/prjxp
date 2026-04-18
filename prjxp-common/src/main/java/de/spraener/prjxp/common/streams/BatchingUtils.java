package de.spraener.prjxp.common.streams;

import java.util.ArrayList;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class BatchingUtils {

    public static <T> Stream<List<T>> pack(Stream<T> stream, int batchSize) {
        Spliterator<T> source = stream.spliterator();

        Spliterator<List<T>> batchSpliterator = new Spliterators.AbstractSpliterator<>(
                source.estimateSize() / batchSize, source.characteristics()) {

            @Override
            public boolean tryAdvance(Consumer<? super List<T>> action) {
                List<T> batch = new ArrayList<>(batchSize);
                for (int i = 0; i < batchSize && source.tryAdvance(batch::add); i++) ;
                if (batch.isEmpty()) return false;
                action.accept(batch);
                return true;
            }
        };

        return StreamSupport.stream(batchSpliterator, false);
    }
}