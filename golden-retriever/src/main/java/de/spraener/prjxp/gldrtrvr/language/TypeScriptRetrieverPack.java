package de.spraener.prjxp.gldrtrvr.language;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.gldrtrvr.code.typescript.TypeScriptRetriever;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class TypeScriptRetrieverPack implements LanguagePack {
    public static final String MIME_TYPE = "text/x-typescript-code";

    private final TypeScriptRetriever typescriptRetriever;

    @Override public String language() { return "typescript"; }
    @Override public String mimeType() { return MIME_TYPE; }
    @Override public Optional<GoldenRetriever> retriever() { return Optional.of(typescriptRetriever); }
}
