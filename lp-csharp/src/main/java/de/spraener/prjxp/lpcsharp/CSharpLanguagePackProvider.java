package de.spraener.prjxp.lpcsharp;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackProvider;

/**
 * SPI entry point for the C# Language Pack.
 *
 * <p>Declared in {@code META-INF/services/de.spraener.prjxp.common.language.LanguagePackProvider};
 * the host instantiates it (no-arg) inside an isolating class loader and calls
 * {@link #create()} once at startup.
 */
public class CSharpLanguagePackProvider implements LanguagePackProvider {

    @Override
    public LanguagePack create() {
        return new CSharpLanguagePack();
    }
}
