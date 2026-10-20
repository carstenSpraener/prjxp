package de.spraener.prjxp.pluginfix.privliba;

import com.example.lib.Lib;
import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackProvider;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxChunker;

import java.io.File;
import java.util.stream.Stream;

/**
 * Test fixture: bundles private library version v1. The chunk content
 * carries the library version so the host can observe which copy the
 * plugin actually saw.
 */
public class PrivLibAPackProvider implements LanguagePackProvider {

    @Override
    public LanguagePack create() {
        return new PrivLibAPack();
    }

    public static class PrivLibAPack implements LanguagePack {
        @Override
        public String language() {
            return "privlib-a";
        }

        @Override
        public String mimeType() {
            return "text/x-privlib-a";
        }

        @Override
        public Stream<PxChunker> findPxChunkers(File f) {
            return Stream.of(new PrivLibAChunker());
        }
    }

    public static class PrivLibAChunker implements PxChunker {
        @Override
        public Stream<PxChunk> chunk(File f) {
            return Stream.of(PxChunk.create(c -> {
                c.setId("privlib-a-1");
                c.setFile(f.getAbsolutePath());
                c.setPart(0);
                c.setTotal(1);
                c.setContent("privlib-a: " + Lib.version());
            }));
        }

        @Override
        public boolean matches(File f) {
            return true;
        }
    }
}
