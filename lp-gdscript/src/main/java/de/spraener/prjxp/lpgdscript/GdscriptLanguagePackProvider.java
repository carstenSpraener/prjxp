package de.spraener.prjxp.lpgdscript;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackProvider;

public class GdscriptLanguagePackProvider implements LanguagePackProvider {
    @Override public LanguagePack create() { return new GdscriptLanguagePack(); }
}
