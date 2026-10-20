package de.spraener.prjxp.pluginfix.good;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackProvider;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxChunker;

import java.io.File;
import java.util.stream.Stream;

/**
 * Test fixture: a well-behaved external pack that produces real PxChunks.
 * Compiled at test time against the host classpath and packaged into a JAR.
 */
public class GoodPackProvider implements LanguagePackProvider {

    @Override
    public LanguagePack create() {
        return new GoodPack();
    }

    public static class GoodPack implements LanguagePack {
        @Override
        public String language() {
            return "goodlang";
        }

        @Override
        public String mimeType() {
            return "text/x-goodlang-code";
        }

        @Override
        public Stream<PxChunker> findPxChunkers(File f) {
            return Stream.of(new GoodChunker());
        }
    }

    public static class GoodChunker implements PxChunker {
        @Override
        public Stream<PxChunk> chunk(File f) {
            return Stream.of(PxChunk.create(c -> {
                c.setId("good-1");
                c.setMimeType("text/x-goodlang-code");
                c.setFile(f.getAbsolutePath());
                c.setPart(0);
                c.setTotal(1);
                c.setContent("hello from the plugin");
            }));
        }

        @Override
        public boolean matches(File f) {
            return f.getName().endsWith(".good");
        }
    }
}
