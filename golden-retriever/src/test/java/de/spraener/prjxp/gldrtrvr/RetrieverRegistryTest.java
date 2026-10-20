package de.spraener.prjxp.gldrtrvr;

import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackRegistry;
import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.model.SearchHit;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RetrieverRegistryTest {

    /* --- Test 1: Only Spring-packs, sorted by language() --- */
    @Test
    void onlySpringPacks_sortedByLanguage() {
        FakeRetriever a = new FakeRetriever("a");
        FakeRetriever b = new FakeRetriever("b");

        LanguagePack packA = new SimpleLanguagePack("a", "text/a", a);
        LanguagePack packB = new SimpleLanguagePack("b", "text/b", b);

        RetrieverRegistry registry = new RetrieverRegistry(
                new LanguagePackRegistry(List.of(packA, packB), List.of()), List.of());
        List<GoldenRetriever> result = registry.all();

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isSameAs(a);
        assertThat(result.get(1)).isSameAs(b);
    }

    /* --- Test 2: Plain bean appended at end --- */
    @Test
    void plainBean_appendedAtEnd() {
        FakeRetriever packRetriever = new FakeRetriever("java");
        FakeRetriever plainBean = new FakeRetriever("markdown");

        LanguagePack pack = new SimpleLanguagePack("java", "text/x-java", packRetriever);

        RetrieverRegistry registry = new RetrieverRegistry(
                new LanguagePackRegistry(List.of(pack), List.of()), List.of(plainBean));
        List<GoldenRetriever> result = registry.all();

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isSameAs(packRetriever);
        assertThat(result.get(1)).isSameAs(plainBean);
    }

    /* --- Test 3: Bean instance already provided by a pack → no duplication --- */
    @Test
    void beanAlreadyInPack_noDuplication() {
        FakeRetriever shared = new FakeRetriever("java");

        LanguagePack pack = new SimpleLanguagePack("java", "text/x-java", shared);

        // The same instance is also injected as a plain bean
        RetrieverRegistry registry = new RetrieverRegistry(
                new LanguagePackRegistry(List.of(pack), List.of()), List.of(shared));
        List<GoldenRetriever> result = registry.all();

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(shared);
    }

    /* --- Test 4: Plugin pack + built-in pack with same language → plugin wins --- */
    @Test
    void pluginPackOverridesBuiltIn_sameLanguage() {
        FakeRetriever builtInRetriever = new FakeRetriever("testlang");
        FakeRetriever pluginRetriever = new FakeRetriever("testlang-plugin");

        LanguagePack builtInPack = new SimpleLanguagePack("testlang", "text/x-testlang", builtInRetriever);
        LanguagePack pluginPack = new SimpleLanguagePack("testlang", "text/x-testlang", pluginRetriever);

        RetrieverRegistry registry = new RetrieverRegistry(
                new LanguagePackRegistry(List.of(builtInPack), List.of(pluginPack)), List.of());
        List<GoldenRetriever> result = registry.all();

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isSameAs(pluginRetriever);
    }

    /* --- Test 5: Plugin pack for a new language → included --- */
    @Test
    void pluginPackForNewLanguage_included() {
        FakeRetriever javaRetriever = new FakeRetriever("java");
        FakeRetriever cobolRetriever = new FakeRetriever("cobol");

        LanguagePack javaPack = new SimpleLanguagePack("java", "text/x-java", javaRetriever);
        LanguagePack cobolPack = new SimpleLanguagePack("cobol", "text/x-cobol", cobolRetriever);

        RetrieverRegistry registry = new RetrieverRegistry(
                new LanguagePackRegistry(List.of(javaPack), List.of(cobolPack)), List.of());
        List<GoldenRetriever> result = registry.all();

        assertThat(result).hasSize(2);
        assertThat(result).containsExactlyInAnyOrder(javaRetriever, cobolRetriever);
    }

    /* --- Test 6: Multiple plain beans, none in packs --- */
    @Test
    void multiplePlainBeans_appendedInOrder() {
        FakeRetriever plain1 = new FakeRetriever("markdown");
        FakeRetriever plain2 = new FakeRetriever("yaml");

        RetrieverRegistry registry = new RetrieverRegistry(
                new LanguagePackRegistry(List.of(), List.of()), List.of(plain1, plain2));
        List<GoldenRetriever> result = registry.all();

        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isSameAs(plain1);
        assertThat(result.get(1)).isSameAs(plain2);
    }

    /* --- Test 7: Mixed — packs sorted, plain beans appended --- */
    @Test
    void mixedPacksAndBeans_correctOrder() {
        FakeRetriever javaR = new FakeRetriever("java");
        FakeRetriever tsR  = new FakeRetriever("typescript");
        FakeRetriever mdR  = new FakeRetriever("markdown");

        LanguagePack javaPack = new SimpleLanguagePack("java", "text/x-java", javaR);
        LanguagePack tsPack   = new SimpleLanguagePack("typescript", "text/x-ts", tsR);

        RetrieverRegistry registry = new RetrieverRegistry(
                new LanguagePackRegistry(List.of(tsPack, javaPack), List.of()), List.of(mdR));
        List<GoldenRetriever> result = registry.all();

        assertThat(result).hasSize(3);
        // TreeMap sorts by key: "java" < "typescript"
        assertThat(result.get(0)).isSameAs(javaR);
        assertThat(result.get(1)).isSameAs(tsR);
        assertThat(result.get(2)).isSameAs(mdR);
    }

    // --- Helpers ---

    private static class FakeRetriever implements GoldenRetriever {
        private final String name;

        FakeRetriever(String name) { this.name = name; }

        @Override
        public StringBuilder buildPromptForFindings(String projectName, List<ScoredChunk> chunks, SearchParams params, Function<String, Boolean>... contextValidators) {
            return new StringBuilder(name);
        }

        @Override
        public List<SearchHit> retrieveSearchHits(String projectName, List<ScoredChunk> chunks, Function<String, Boolean>... contextValidators) {
            return List.of();
        }
    }

    private static class SimpleLanguagePack implements LanguagePack {
        private final String language;
        private final String mimeType;
        private final GoldenRetriever retriever;

        SimpleLanguagePack(String language, String mimeType, GoldenRetriever retriever) {
            this.language = language;
            this.mimeType = mimeType;
            this.retriever = retriever;
        }

        @Override public String language() { return language; }
        @Override public String mimeType() { return mimeType; }
        @Override public Optional<GoldenRetriever> retriever() { return Optional.ofNullable(retriever); }
        @Override public Stream<PxChunker> findPxChunkers(File f) { return Stream.empty(); }
        @Override public Stream<PxChunker> listPostWalkChunkers() { return Stream.empty(); }
    }
}
