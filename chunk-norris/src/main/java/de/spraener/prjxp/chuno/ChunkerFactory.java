package de.spraener.prjxp.chuno;

import de.spraener.prjxp.chuno.language.LanguagePackBroker;
import de.spraener.prjxp.common.model.PxChunker;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;

@Service
public class ChunkerFactory extends AnnotationBasedChunkerBrokerImpl {
    private final LanguagePackBroker languagePackBroker;
    private final DefaultLineBreakTextChunker defaultLineBreakTextChunker;
    private List<ChunkerBroker> brokerList;

    public ChunkerFactory(LanguagePackBroker languagePackBroker,
                          DefaultLineBreakTextChunker defaultLineBreakTextChunker) {
        super(ChunkerFactory.class.getPackageName());
        this.languagePackBroker = languagePackBroker;
        this.defaultLineBreakTextChunker = defaultLineBreakTextChunker;
    }

    private void initBrokerList() {
        this.brokerList = new ArrayList<>();
        brokerList.add(this);
        brokerList.add(languagePackBroker);
        ServiceLoader<ChunkerBroker> chunkerBrokers = ServiceLoader.load(ChunkerBroker.class);
        for (ChunkerBroker broker : chunkerBrokers) {
            brokerList.add(broker);
        }
    }

    public Stream<PxChunker> createChunker(File f) {
        if (brokerList == null) {
            initBrokerList();
        }
        List<PxChunker> matching = brokerList.stream()
                .flatMap(broker -> broker.findPxChunkers(f))
                .toList();
        // The always-matching FullTextChunker is a catch-all, not a "specific" chunker.
        // (It is the only one today; a new catch-all chunker would need to be excluded here too.)
        boolean hasSpecific = matching.stream().anyMatch(c -> !(c instanceof FullTextChunker));
        if (hasSpecific) {
            return matching.stream();
        }
        // Fallback: whitelisted file for which no specific chunker matches — without this it
        // would be silently dropped (see doc/Tasks/DefaultLineBreakTextChunker.md). The
        // FullTextChunker (if present) stays in the stream; it is gated per project downstream.
        return Stream.concat(matching.stream(), Stream.of(defaultLineBreakTextChunker));
    }

    @Override
    public Stream<PxChunker> listPostWalkChunker() {
        return Stream.concat(super.listPostWalkChunker(), languagePackBroker.listPostWalkChunker());
    }
}
