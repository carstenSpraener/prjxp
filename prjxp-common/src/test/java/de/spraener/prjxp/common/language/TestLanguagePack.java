package de.spraener.prjxp.common.language;

import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;

import java.io.File;
import java.util.Optional;
import java.util.stream.Stream;

public class TestLanguagePack implements LanguagePack {
    @Override public String language() { return "testlang"; }
    @Override public String mimeType() { return "text/x-testlang"; }
    // Default-Methoden (leer) sind ausreichend für die Tests
}
