package de.spraener.prjxp.chuno.language;

import de.spraener.prjxp.chuno.ChunkerBroker;
import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackRegistry;
import de.spraener.prjxp.common.model.PxChunker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.stream.Stream;

/** Bridge: stellt die gemeinsame LanguagePackRegistry (Spring-Beans + Plugins) als ChunkerBroker bereit. */
@Component
@RequiredArgsConstructor
public class LanguagePackBroker implements ChunkerBroker {

    private final LanguagePackRegistry languagePackRegistry;

    @Override
    public Stream<PxChunker> findPxChunkers(File f) {
        return languagePackRegistry.all().stream()
                .flatMap(pack -> pack.findPxChunkers(f));
    }

    @Override
    public Stream<PxChunker> listPostWalkChunker() {
        return languagePackRegistry.all().stream()
                .flatMap(LanguagePack::listPostWalkChunkers);
    }
}
