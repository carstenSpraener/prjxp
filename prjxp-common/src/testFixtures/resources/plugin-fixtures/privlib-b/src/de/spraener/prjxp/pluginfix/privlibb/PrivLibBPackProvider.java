package de.spraener.prjxp.pluginfix.privlibb;

import com.example.lib.Lib;
import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackProvider;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxChunker;

import java.io.File;
import java.util.stream.Stream;

/**
 * Test fixture: bundles private library version v2 (incompatible with
 * the copy bundled by privlib-a).
 */
public class PrivLibBPackProvider implements LanguagePackProvider {

    @Override
    public LanguagePack create() {
        return new PrivLibBPack();
    }

    public static class PrivLibBPack implements LanguagePack {
        @Override
        public String language() {
            return "privlib-b";
        }

        @Override
        public String mimeType() {
            return "text/x-privlib-b";
        }

        @Override
        public Stream<PxChunker> findPxChunkers(File f) {
            return Stream.of(new PrivLibBChunker());
        }
    }

    public static class PrivLibBChunker implements PxChunker {
        @Override
        public Stream<PxChunk> chunk(File f) {
            return Stream.of(PxChunk.create(c -> {
                c.setId("privlib-b-1");
                c.setFile(f.getAbsolutePath());
                c.setPart(0);
                c.setTotal(1);
                c.setContent("privlib-b: " + Lib.version());
            }));
        }

        @Override
        public boolean matches(File f) {
            return true;
        }
    }
}
