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
    private List<ChunkerBroker> brokerList;

    public ChunkerFactory(LanguagePackBroker languagePackBroker) {
        super(ChunkerFactory.class.getPackageName());
        this.languagePackBroker = languagePackBroker;
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
        return brokerList.stream()
                .flatMap(broker -> broker.findPxChunkers(f));
    }

    @Override
    public Stream<PxChunker> listPostWalkChunker() {
        return Stream.concat(super.listPostWalkChunker(), languagePackBroker.listPostWalkChunker());
    }
}
