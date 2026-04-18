package de.spraener.chuno;

import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Stream;

@Service
public class ChunkerFactory extends AnnotationBasedChunkerBrokerImpl {
    private List<ChunkerBroker> brokerList;

    public ChunkerFactory() {
        super(ChunkerFactory.class.getPackageName());
    }

    /**
     * Initialisiert die interne Liste der {@link ChunkerBroker}.
     * Diese Methode fügt zuerst die aktuelle Instanz der {@link ChunkerFactory}
     * zur Liste hinzu und lädt anschließend alle weiteren {@link ChunkerBroker}-Implementierungen,
     * die über den Java {@link java.util.ServiceLoader} registriert sind.
     * Dies ermöglicht es der {@link ChunkerFactory}, Anfragen an alle verfügbaren Chunker-Broker zu delegieren.
     */
    private void initBrokerList() {
        this.brokerList = new ArrayList<>();
        brokerList.add(this);
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
}
