package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.language.LanguagePack;

public class TestLanguagePack implements LanguagePack {
    @Override public String language() { return "cobol"; }
    @Override public String mimeType() { return "text/x-cobol"; }
}
