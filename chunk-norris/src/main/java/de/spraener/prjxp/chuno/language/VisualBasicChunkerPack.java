package de.spraener.prjxp.chuno.language;

import de.spraener.prjxp.chuno.code.visualbasic.VisualBasicCodeChunker;
import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.model.PxChunker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class VisualBasicChunkerPack implements LanguagePack {
    private final VisualBasicCodeChunker codeChunker;

    @Override public String language() { return "visualbasic"; }
    @Override public String mimeType() { return VisualBasicCodeChunker.VISUAL_BASIC_CODE_MIME_TYPE; }

    @Override
    public Stream<PxChunker> findPxChunkers(File f) {
        return Stream.of((PxChunker) codeChunker).filter(c -> c.matches(f));
    }
}
