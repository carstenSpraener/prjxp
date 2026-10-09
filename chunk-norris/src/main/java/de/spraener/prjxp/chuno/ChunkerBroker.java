package de.spraener.prjxp.chuno;

import de.spraener.prjxp.common.model.PxChunker;

import java.io.File;
import java.util.stream.Stream;

public interface ChunkerBroker {
    Stream<PxChunker> findPxChunkers(File f);

    Stream<PxChunker> listPostWalkChunker();
}
