package de.spraener.prjxp.gldrtrvr.language;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.gldrtrvr.code.java.JavaRetriever;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JavaRetrieverPack implements LanguagePack {
    public static final String MIME_TYPE = "text/x-java-code";

    private final JavaRetriever javaRetriever;

    @Override public String language() { return "java"; }
    @Override public String mimeType() { return MIME_TYPE; }
    @Override public Optional<GoldenRetriever> retriever() { return Optional.of(javaRetriever); }
}
