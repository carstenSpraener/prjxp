package de.spraener.prjxp.lpgdscript;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackProvider;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the mavenLocal prjxp-common artifact exposes the plugin contract. */
class PrjxpCommonContractTest {
    @Test
    void exposesPluginContract() {
        assertThat(LanguagePack.class).isNotNull();
        assertThat(LanguagePackProvider.class).isNotNull();
        assertThat(PxChunker.class).isNotNull();
        assertThat(PxChunk.class).isNotNull();
        assertThat(GoldenRetriever.class).isNotNull();
    }
}
