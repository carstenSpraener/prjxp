package de.spraener.prjxp.gldrtrvr.language;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.gldrtrvr.code.visualbasic.VisualBasicRetriever;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class VisualBasicRetrieverPack implements LanguagePack {
    public static final String MIME_TYPE = "text/x-visual-basic-code";

    private final VisualBasicRetriever visualBasicRetriever;

    @Override public String language() { return "visualbasic"; }
    @Override public String mimeType() { return MIME_TYPE; }
    @Override public Optional<GoldenRetriever> retriever() { return Optional.of(visualBasicRetriever); }
}
