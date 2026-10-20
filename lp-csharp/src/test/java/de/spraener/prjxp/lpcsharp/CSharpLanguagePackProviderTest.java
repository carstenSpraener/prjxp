package de.spraener.prjxp.lpcsharp;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CSharpLanguagePackProviderTest {

    private final CSharpLanguagePackProvider provider = new CSharpLanguagePackProvider();

    @Test
    void createReturnsACSharpPack() {
        LanguagePack pack = provider.create();
        assertThat(pack).isInstanceOf(CSharpLanguagePack.class);
        assertThat(pack.language()).isEqualTo("csharp");
        assertThat(pack.mimeType()).isEqualTo("text/x-csharp-code");
    }

    @Test
    void packProvidesChunkerForCsFilesOnly() {
        LanguagePack pack = provider.create();

        List<PxChunker> cs = pack.findPxChunkers(new File("Foo.cs")).toList();
        assertThat(cs).hasSize(1);
        assertThat(cs.get(0)).isInstanceOf(CSharpCodeChunker.class);

        assertThat(pack.findPxChunkers(new File("Foo.java")).toList()).isEmpty();
    }

    @Test
    void packProvidesRetriever() {
        LanguagePack pack = provider.create();
        assertThat(pack.retriever()).isPresent();
        assertThat(pack.retriever().orElseThrow()).isInstanceOf(CSharpRetriever.class);
    }

    @Test
    void createIsRepeatable() {
        // The host calls create() once, but it must be safe to call again.
        LanguagePack a = provider.create();
        LanguagePack b = provider.create();
        assertThat(a).isNotSameAs(b);
        assertThat(a.language()).isEqualTo(b.language());
    }

    @Test
    void bindRecreatesRetrieverWithStoreAccess() {
        LanguagePack pack = provider.create();
        GoldenRetriever initial = pack.retriever().orElseThrow();

        pack.bind(new PxChunkDaoProvider(List.of()));

        GoldenRetriever bound = pack.retriever().orElseThrow();
        assertThat(bound).isInstanceOf(CSharpRetriever.class);
        assertThat(bound)
                .as("bind() must swap in a store-aware retriever")
                .isNotSameAs(initial);
    }

    @Test
    void bindWithNullKeepsSelfContainedRetriever() {
        LanguagePack pack = provider.create();
        GoldenRetriever initial = pack.retriever().orElseThrow();

        pack.bind(null);

        GoldenRetriever bound = pack.retriever().orElseThrow();
        assertThat(bound).isInstanceOf(CSharpRetriever.class);
        assertThat(bound).isNotSameAs(initial);   // still a fresh, self-contained instance
    }
}
