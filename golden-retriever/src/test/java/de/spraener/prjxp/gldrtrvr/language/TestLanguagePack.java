package de.spraener.prjxp.gldrtrvr.language;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;

import java.io.File;
import java.util.Optional;
import java.util.stream.Stream;

public class TestLanguagePack implements LanguagePack {
    @Override public String language() { return "testlang"; }
    @Override public String mimeType() { return "text/x-testlang"; }

    @Override public Stream<PxChunker> findPxChunkers(File f) { return Stream.empty(); }
    @Override public Stream<PxChunker> listPostWalkChunkers() { return Stream.empty(); }
    @Override public Optional<GoldenRetriever> retriever() { return Optional.empty(); }
}
