package de.spraener.prjxp.chuno.language;

import de.spraener.prjxp.chuno.code.java.JavaCodeChunker;
import de.spraener.prjxp.chuno.code.java.JavaCodeGraphBuilder;
import de.spraener.prjxp.chuno.code.java.JavaDependenciesChunker;
import de.spraener.prjxp.common.language.CodeGraphBuilder;
import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.model.PxChunker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Optional;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class JavaChunkerPack implements LanguagePack {
    private final JavaCodeChunker codeChunker;
    private final JavaDependenciesChunker dependenciesChunker;
    private final JavaCodeGraphBuilder javaCodeGraphBuilder;

    @Override public String language() { return "java"; }
    @Override public String mimeType() { return JavaCodeChunker.JAVA_CODE_MIME_TYPE; }

    @Override
    public Stream<PxChunker> findPxChunkers(File f) {
        return Stream.of((PxChunker) codeChunker).filter(c -> c.matches(f));
    }

    @Override
    public Stream<PxChunker> listPostWalkChunkers() {
        return Stream.of((PxChunker) dependenciesChunker);
    }

    @Override
    public Optional<CodeGraphBuilder> codeGraph() {
        return Optional.of(javaCodeGraphBuilder);
    }
}
