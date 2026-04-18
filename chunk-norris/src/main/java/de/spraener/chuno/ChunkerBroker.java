package de.spraener.chuno;

import java.io.File;
import java.util.stream.Stream;

public interface ChunkerBroker {
    Stream<PxChunker> findPxChunkers(File f);

    Stream<PxChunker> listPostWalkChunker();
}
