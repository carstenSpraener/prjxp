package de.spraener.prjxp.chuno.language;

import de.spraener.prjxp.chuno.code.typescript.TypeScriptCodeChunker;
import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.model.PxChunker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class TypeScriptChunkerPack implements LanguagePack {
    private final TypeScriptCodeChunker codeChunker;

    @Override public String language() { return "typescript"; }
    @Override public String mimeType() { return TypeScriptCodeChunker.TYPESCRIPT_CODE_MIME_TYPE; }

    @Override
    public Stream<PxChunker> findPxChunkers(File f) {
        return Stream.of((PxChunker) codeChunker).filter(c -> c.matches(f));
    }
}
