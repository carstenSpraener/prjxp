package de.spraener.prjxp.chuno.language;

import de.spraener.prjxp.chuno.ChunkerBroker;
import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePacks;
import de.spraener.prjxp.common.model.PxChunker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;
import java.util.stream.Stream;

/** Bridge: stellt Language-Packs (Spring-Beans + externe ServiceLoader-Packs) als ChunkerBroker bereit. */
@Component
@RequiredArgsConstructor
public class LanguagePackBroker implements ChunkerBroker {

    private final List<LanguagePack> languagePacks;   // Spring injiziert alle Pack-Beans

    @Override
    public Stream<PxChunker> findPxChunkers(File f) {
        return LanguagePacks.merge(languagePacks).stream()
                .flatMap(pack -> pack.findPxChunkers(f));
    }

    @Override
    public Stream<PxChunker> listPostWalkChunker() {
        return LanguagePacks.merge(languagePacks).stream()
                .flatMap(LanguagePack::listPostWalkChunkers);
    }
}
