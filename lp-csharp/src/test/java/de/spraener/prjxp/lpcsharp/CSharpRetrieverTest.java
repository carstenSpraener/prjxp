package de.spraener.prjxp.lpcsharp;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CSharpRetrieverTest {

    private static final String MIME = "text/x-csharp-code";

    private PxChunk csharp(String id, String parent, CSharpCodeSection section, String content) {
        return PxChunk.create(
                c -> c.setId(id),
                c -> c.setMimeType(MIME),
                c -> c.setFile("/proj/src/Foo.cs"),
                c -> { if (parent != null) c.setParent(parent); },
                c -> c.setContent(content),
                c -> c.getMetadata().put(CSharpCodeSection.METADATA_KEY, section.getName()));
    }

    private PxChunk javaChunk(String id) {
        return PxChunk.create(
                c -> c.setId(id),
                c -> c.setMimeType("text/x-java-code"),
                c -> c.setContent("class Bar {}"),
                c -> c.getMetadata().put("java_code_section", "classFrame"));
    }

    private PxChunkDaoProvider providerFor(String project, PxChunk... stored) {
        InMemoryChunkDao dao = new InMemoryChunkDao(project);
        for (PxChunk c : stored) {
            dao.add(c);
        }
        return new PxChunkDaoProvider(List.of(dao));
    }

    // ------------------------------------------------------------------
    // Filtering
    // ------------------------------------------------------------------

    @Test
    void filtersOnlyCSharpChunks() {
        CSharpRetriever retriever = new CSharpRetriever();
        List<SearchHit> hits = retriever.retrieveSearchHits("proj", List.of(
                new ScoredChunk(csharp("Acme.Foo", null, CSharpCodeSection.TYPE_FRAME, "class Foo {}"), 0.9),
                new ScoredChunk(javaChunk("com.Bar"), 0.85)));

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).chunkId()).isEqualTo("Acme.Foo");
        assertThat(hits.get(0).source()).isEqualTo("csharp");
    }

    @Test
    void returnsEmptyWhenNoCSharpChunks() {
        CSharpRetriever retriever = new CSharpRetriever();
        assertThat(retriever.retrieveSearchHits("proj", List.of(new ScoredChunk(javaChunk("com.Bar"), 0.9)))).isEmpty();
        assertThat(retriever.buildPromptForFindings("proj", List.of(new ScoredChunk(javaChunk("com.Bar"), 0.9)),
                new SearchParams(8, 0.85)).toString()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Part reassembly
    // ------------------------------------------------------------------

    @Test
    void reassemblesSplitPartsFromInput() {
        CSharpRetriever retriever = new CSharpRetriever();
        PxChunk p1 = csharp("Acme.Foo.bar", "Acme.Foo", CSharpCodeSection.METHOD, "void bar() { part-one");
        p1.setPart(0);
        p1.setTotal(2);
        PxChunk p2 = csharp("Acme.Foo.bar", "Acme.Foo", CSharpCodeSection.METHOD, "part-two }");
        p2.setPart(1);
        p2.setTotal(2);

        List<SearchHit> hits = retriever.retrieveSearchHits("proj", List.of(
                new ScoredChunk(p1, 0.9),
                new ScoredChunk(p2, 0.85)));

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).snippet()).contains("part-one").contains("part-two");
    }

    @Test
    void fetchesMissingPartsFromStore() {
        // Only part 0 is returned by the vector search; part 1 must come from the store.
        PxChunk p1 = csharp("Acme.Foo.bar", "Acme.Foo", CSharpCodeSection.METHOD, "void bar() { part-one");
        p1.setPart(0);
        p1.setTotal(2);
        PxChunk p2 = csharp("Acme.Foo.bar", "Acme.Foo", CSharpCodeSection.METHOD, "part-two }");
        p2.setPart(1);
        p2.setTotal(2);

        CSharpRetriever retriever = new CSharpRetriever(providerFor("proj", p1, p2));
        List<SearchHit> hits = retriever.retrieveSearchHits("proj", List.of(new ScoredChunk(p1, 0.9)));

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).snippet()).contains("part-one").contains("part-two");
    }

    // ------------------------------------------------------------------
    // Parent hierarchy / enrichment
    // ------------------------------------------------------------------

    @Test
    void enrichesMethodWithParentFrameFromStore() {
        PxChunk frame = csharp("Acme.Foo", null, CSharpCodeSection.TYPE_FRAME, "class Foo { int x; }");
        PxChunk method = csharp("Acme.Foo.bar", "Acme.Foo", CSharpCodeSection.METHOD, "void bar() {}");

        CSharpRetriever retriever = new CSharpRetriever(providerFor("proj", frame, method));
        // Only the method is a hit; the frame is fetched from the store as context.
        List<SearchHit> hits = retriever.retrieveSearchHits("proj", List.of(new ScoredChunk(method, 0.9)));

        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).snippet()).contains("void bar()").contains("class Foo");
    }

    @Test
    void doesNotDuplicateParentThatIsAlreadyAHit() {
        PxChunk frame = csharp("Acme.Foo", null, CSharpCodeSection.TYPE_FRAME, "class Foo { int x; }");
        PxChunk method = csharp("Acme.Foo.bar", "Acme.Foo", CSharpCodeSection.METHOD, "void bar() {}");

        CSharpRetriever retriever = new CSharpRetriever();
        String prompt = retriever.buildPromptForFindings("proj", List.of(
                new ScoredChunk(method, 0.9),
                new ScoredChunk(frame, 0.8)), new SearchParams(8, 0.85)).toString();

        // The frame appears exactly once (as its own hit), not duplicated into the method.
        assertThat(prompt).contains("class Foo { int x; }");
        assertThat(prompt).contains("void bar()");
        assertThat(countOccurrences(prompt, "class Foo { int x; }")).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Prompt content: frame, method/constructor, doc
    // ------------------------------------------------------------------

    @Test
    void promptContainsFrameMethodConstructorAndDoc() {
        CSharpRetriever retriever = new CSharpRetriever();
        String prompt = retriever.buildPromptForFindings("proj", List.of(
                new ScoredChunk(csharp("Acme.Foo", null, CSharpCodeSection.TYPE_FRAME, "class Foo {}"), 0.95),
                new ScoredChunk(csharp("Acme.Foo.bar", "Acme.Foo", CSharpCodeSection.METHOD, "void bar() {}"), 0.9),
                new ScoredChunk(csharp("Acme.Foo.Foo", "Acme.Foo", CSharpCodeSection.CONSTRUCTOR, "Foo() {}"), 0.85),
                new ScoredChunk(csharp("Acme.Foo.bar.doc", "Acme.Foo", CSharpCodeSection.METHOD_DOC, "/// does bar"), 0.8)),
                new SearchParams(8, 0.85)).toString();

        assertThat(prompt).contains("class Foo {}");
        assertThat(prompt).contains("void bar() {}");
        assertThat(prompt).contains("Foo() {}");
        assertThat(prompt).contains("/// does bar");
        assertThat(prompt).contains("[method]").contains("[constructor]").contains("[typeFrame]");
    }

    // ------------------------------------------------------------------
    // Budget, result cap and deterministic ordering
    // ------------------------------------------------------------------

    @Test
    void respectsMaxResultCap() {
        CSharpRetriever retriever = new CSharpRetriever();
        List<ScoredChunk> chunks = List.of(
                new ScoredChunk(csharp("Acme.A", null, CSharpCodeSection.TYPE_FRAME, "class A {}"), 0.95),
                new ScoredChunk(csharp("Acme.B", null, CSharpCodeSection.TYPE_FRAME, "class B {}"), 0.9),
                new ScoredChunk(csharp("Acme.C", null, CSharpCodeSection.TYPE_FRAME, "class C {}"), 0.85));

        String prompt = retriever.buildPromptForFindings("proj", chunks, new SearchParams(2, 0.85)).toString();
        assertThat(prompt).contains("class A {}").contains("class B {}");
        assertThat(prompt).doesNotContain("class C {}");
    }

    @Test
    void respectsContentBudget() {
        CSharpRetriever retriever = new CSharpRetriever();
        String big = "x".repeat(60);
        List<ScoredChunk> chunks = List.of(
                new ScoredChunk(csharp("Acme.A", null, CSharpCodeSection.TYPE_FRAME, big), 0.95),
                new ScoredChunk(csharp("Acme.B", null, CSharpCodeSection.TYPE_FRAME, big), 0.9));

        SearchParams params = new SearchParams(8, 0.85);
        params.setMaxContentLength(100);
        String prompt = retriever.buildPromptForFindings("proj", chunks, params).toString();

        // The first section fits the budget; the second would exceed it and is dropped.
        assertThat(prompt).contains("Acme.A").contains(big);
        assertThat(prompt).doesNotContain("Acme.B");
    }

    @Test
    void sortsByScoreDescendingDeterministically() {
        CSharpRetriever retriever = new CSharpRetriever();
        String prompt = retriever.buildPromptForFindings("proj", List.of(
                new ScoredChunk(csharp("Acme.C", null, CSharpCodeSection.TYPE_FRAME, "class C {}"), 0.85),
                new ScoredChunk(csharp("Acme.A", null, CSharpCodeSection.TYPE_FRAME, "class A {}"), 0.95),
                new ScoredChunk(csharp("Acme.B", null, CSharpCodeSection.TYPE_FRAME, "class B {}"), 0.9)),
                new SearchParams(8, 0.85)).toString();

        int a = prompt.indexOf("class A {}");
        int b = prompt.indexOf("class B {}");
        int c = prompt.indexOf("class C {}");
        assertThat(a).isLessThan(b);
        assertThat(b).isLessThan(c);
    }

    @Test
    void appliesContextValidators() {
        CSharpRetriever retriever = new CSharpRetriever();
        // A validator that rejects any section containing "secret".
        String prompt = retriever.buildPromptForFindings("proj", List.of(
                new ScoredChunk(csharp("Acme.A", null, CSharpCodeSection.TYPE_FRAME, "class A {}"), 0.95),
                new ScoredChunk(csharp("Acme.B", null, CSharpCodeSection.TYPE_FRAME, "class B secret {}"), 0.9)),
                new SearchParams(8, 0.85),
                text -> !text.contains("secret")).toString();

        assertThat(prompt).contains("class A {}");
        assertThat(prompt).doesNotContain("class B secret");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
