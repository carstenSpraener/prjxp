package de.spraener.prjxp.pluginfix.broken;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackProvider;

/**
 * Test fixture: a provider whose create() throws — must not block other JARs.
 */
public class BrokenPackProvider implements LanguagePackProvider {

    @Override
    public LanguagePack create() {
        throw new IllegalStateException("simulated broken provider");
    }
}
