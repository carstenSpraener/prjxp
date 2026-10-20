package de.spraener.prjxp.pluginfix.second;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackProvider;

/**
 * Test fixture: a minimal second pack (for multi-JAR loading).
 */
public class SecondPackProvider implements LanguagePackProvider {

    @Override
    public LanguagePack create() {
        return new SecondPack();
    }

    public static class SecondPack implements LanguagePack {
        @Override
        public String language() {
            return "secondlang";
        }

        @Override
        public String mimeType() {
            return "text/x-secondlang-code";
        }
    }
}
