package de.spraener.prjxp.pluginfix.contractviolation;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackProvider;

/**
 * Test fixture: a pack whose contract method throws — must be rejected
 * at load time with a clear diagnostic, not fail later at chunk time.
 */
public class ContractViolatingProvider implements LanguagePackProvider {

    @Override
    public LanguagePack create() {
        return new ContractViolatingPack();
    }

    public static class ContractViolatingPack implements LanguagePack {
        @Override
        public String language() {
            throw new NoClassDefFoundError("simulated linkage error");
        }

        @Override
        public String mimeType() {
            return "text/x-violating-code";
        }
    }
}
