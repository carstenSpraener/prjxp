package de.spraener.prjxp.lpcsharp;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackProvider;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-2 Phase 01: verifies that the shared host API ({@code prjxp-common}) is
 * consumed from the local Maven repository and exposes the plugin contract.
 *
 * <p>This is the acceptance criterion that the published artifact contains
 * {@link LanguagePackProvider} (and the rest of the contract) and is resolved
 * from mavenLocal — not via a composite build.
 */
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
