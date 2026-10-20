package de.spraener.prjxp.pluginfix.brokenidentity;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackProvider;

/**
 * Test fixture: implements the bundled (child-loaded) copy of LanguagePack.
 * With child-first delegation the host must reject this provider at load
 * time (instanceof host LanguagePack == false) instead of failing later.
 */
public class BrokenIdentityProvider implements LanguagePackProvider {

    @Override
    public LanguagePack create() {
        return new BrokenIdentityPack();
    }

    public static class BrokenIdentityPack implements LanguagePack {
        @Override
        public String language() {
            return "brokenlang";
        }

        @Override
        public String mimeType() {
            return "text/x-brokenlang-code";
        }

        @Override
        public int identityBreakMarker() {
            return 42;
        }
    }
}
