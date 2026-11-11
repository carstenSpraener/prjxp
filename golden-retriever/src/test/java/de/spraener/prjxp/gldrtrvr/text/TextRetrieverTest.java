package de.spraener.prjxp.gldrtrvr.text;

import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TextRetrieverTest {
    private final PrjXPEmbeddingStoreReference ref = new PrjXPEmbeddingStoreReference();
    private final Map<String, List<PxChunk>> db = new HashMap<>();
    private final PxChunkDao dao = new PxChunkDao() {
        @Override
        public PrjXPEmbeddingStoreReference getStoreReference() {
            return ref;
        }

        @Override
        public List<PxChunk> findById(String id) {
            // Return a mutable copy: PxChunk.combine sorts the list in place (like the Lucene DAO).
            List<PxChunk> chunks = db.get(id);
            return chunks == null ? null : new java.util.ArrayList<>(chunks);
        }

        @Override
        public List<PxChunk> findByMetaData(Map<String, String> metaData) {
            return List.of();
        }

        @Override
        public List<PxChunk> findRelevant(String question, int maxResults, double minScore) {
            return List.of();
        }

        @Override
        public Stream<PxChunk> findAll() {
            return Stream.empty();
        }
    };
    private final TextRetriever retriever = new TextRetriever(new PxChunkDaoProvider(List.of(dao)));
    private final SearchParams sp = new SearchParams(20, 0.85);

    @BeforeEach
    void setUp() {
        ref.setProjectName("default");
        ref.setDefault(true);
        db.clear();
    }

    private PxChunk textChunk(String id, String file, String content, int part, int total) {
        return PxChunk.create(
                c -> c.setId(id),
                c -> c.setMimeType(TextRetriever.MIME),
                c -> c.setFile(file),
                c -> c.setContent(content),
                c -> c.setPart(part),
                c -> c.setTotal(total),
                c -> c.getMetadata().put(PxChunk.PXCHUNK_TYPE, PxChunk.TEXT_MARKER));
    }

    @Test
    void textChunkIsPresentedAsPlainTextExcerpt() {
        PxChunk chunk = textChunk("foo.rst:text", "docs/foo.rst", "Hello world\nSecond line", 0, 1);

        String prompt = retriever.buildPromptForFindings("default", List.of(new ScoredChunk(chunk, 0.9)), sp).toString();

        assertThat(prompt).contains("## Textauszug aus docs/foo.rst:");
        assertThat(prompt).contains("Hello world\nSecond line");
    }

    @Test
    void nonTextChunkIsIgnored() {
        PxChunk mdChunk = PxChunk.create(
                c -> c.setId("foo.md"),
                c -> c.setMimeType("text/markdown"),
                c -> c.setFile("docs/foo.md"),
                c -> c.setContent("# Heading"),
                c -> c.setPart(0),
                c -> c.setTotal(1));

        String prompt = retriever.buildPromptForFindings("default", List.of(new ScoredChunk(mdChunk, 0.9)), sp).toString();

        assertThat(prompt).isEmpty();
    }

    @Test
    void missingPartsAreLoadedFromStoreAndCombined() {
        // Two parts of the same file (same ID); only part 0 is in the findings.
        PxChunk part0 = textChunk("foo.rst:text", "docs/foo.rst", "line1\nline2\n", 0, 2);
        PxChunk part1 = textChunk("foo.rst:text", "docs/foo.rst", "line3\nline4", 1, 2);
        db.put("foo.rst:text", List.of(part0, part1));

        // Only part 0 is retrieved; the retriever loads part 1 from the store and recombines.
        String prompt = retriever.buildPromptForFindings("default", List.of(new ScoredChunk(part0, 0.9)), sp).toString();

        assertThat(prompt).contains("## Textauszug aus docs/foo.rst:");
        assertThat(prompt).contains("line1\nline2\nline3\nline4");   // the whole file, recombined
    }

    @Test
    void textChunkWithGeneratedAtShowsFreshnessNote() {
        PxChunk chunk = textChunk("foo.rst:text", "docs/foo.rst", "Hello world", 0, 1);
        chunk.getMetadata().put("generated_at", "1700000000000");

        String prompt = retriever.buildPromptForFindings("default", List.of(new ScoredChunk(chunk, 0.9)), sp).toString();

        assertThat(prompt).contains("Index-Stand");
    }

    @Test
    void textChunkWithoutGeneratedAtHasNoFreshnessNote() {
        PxChunk chunk = textChunk("foo.rst:text", "docs/foo.rst", "Hello world", 0, 1);

        String prompt = retriever.buildPromptForFindings("default", List.of(new ScoredChunk(chunk, 0.9)), sp).toString();

        assertThat(prompt).doesNotContain("Index-Stand");
    }

    @Test
    void contentOverBudgetIsTruncatedWithNotice() {
        SearchParams tinyBudget = new SearchParams(20, 0.85);
        tinyBudget.setMaxContentLength(20);   // tiny budget
        PxChunk chunk = textChunk("big.rst:text", "docs/big.rst", "x".repeat(100), 0, 1);

        String prompt = retriever.buildPromptForFindings("default", List.of(new ScoredChunk(chunk, 0.9)), tinyBudget).toString();

        assertThat(prompt).doesNotContain("x".repeat(100));
        assertThat(prompt).contains("Groessenlimit");
    }

    // ---- Phase 03b: doc-chunk windowing (vectorSearch path) ----

    // 22 KB text/plain chunk -> windowed + marker, full content not in the prompt
    @Test
    void largeTextChunkIsWindowedWithMarker() {
        String content = "lorem ipsum dolor\n".repeat(1200);   // ~21.6 KB
        PxChunk chunk = textChunk("big.rst:text", "docs/big.rst", content, 0, 1);

        String prompt = retriever.buildPromptForFindings("default", List.of(new ScoredChunk(chunk, 0.9)), sp).toString();

        assertThat(prompt).contains("readFile(\"docs/big.rst\")");
        assertThat(prompt).doesNotContain(content);
        assertThat(prompt.length()).isLessThan(content.length());
    }

    // 1 KB text/plain chunk (< docMaxChars) -> unchanged, no marker
    @Test
    void smallTextChunkIsUnchangedWithoutMarker() {
        String content = "x\n".repeat(500);                            // 1000 chars < 2000
        PxChunk chunk = textChunk("small.rst:text", "docs/small.rst", content, 0, 1);

        String prompt = retriever.buildPromptForFindings("default", List.of(new ScoredChunk(chunk, 0.9)), sp).toString();

        assertThat(prompt).contains(content);
        assertThat(prompt).doesNotContain("readFile");
    }

    // ---- Phase 03c: doc-specific content budget (vectorSearch path) ----

    /** 8 text/plain docs of ~2.2 KB each (all > docMaxChars, so each is windowed to ~2 KB). */
    private List<ScoredChunk> eightTextDocs() {
        List<ScoredChunk> chunks = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            String content = "lorem ipsum dolor\n".repeat(123);   // 2214 chars > docMaxChars (2000)
            PxChunk chunk = textChunk("doc" + i + ".rst:text", "docs/doc" + i + ".rst", content, 0, 1);
            db.put("doc" + i + ".rst:text", List.of(chunk));
            chunks.add(new ScoredChunk(chunk, 0.9));
        }
        return chunks;
    }

    // 8 windowed docs with the 50 KB code budget -> the 8 KB doc cap applies, not the code budget
    @Test
    void docBudgetCapsTotalDocVolume() {
        SearchParams params = new SearchParams(20, 0.85);
        params.setMaxContentLength(50000);

        String prompt = retriever.buildPromptForFindings("default", eightTextDocs(), params).toString();

        assertThat(prompt.length())
                .as("8 windowed docs must be capped at docMaxContentLength + skip marker")
                .isLessThanOrEqualTo(8000 + 200);
        assertThat(prompt).contains("Groessenlimit");
        // each windowed doc is > 2 KB, so only 3 fit into the 8 KB budget
        // (the windowing marker appears once per included doc)
        assertThat(prompt.split("readFile\\(", -1).length - 1).isEqualTo(3);
    }

    // maxContentLength smaller than the doc cap -> the smaller value wins (min behavior)
    @Test
    void smallerMaxContentLengthWinsOverDocCap() {
        SearchParams params = new SearchParams(20, 0.85);
        params.setMaxContentLength(5000);

        String prompt = retriever.buildPromptForFindings("default", eightTextDocs(), params).toString();

        assertThat(prompt.length()).isLessThanOrEqualTo(5000 + 200);
        assertThat(prompt).contains("Groessenlimit");
    }

    // 8 x 2.2 KB docs -> total prompt < 10 KB (was ~18 KB before the doc budget)
    @Test
    void eightDocsStayBelowTenKb() {
        SearchParams params = new SearchParams(20, 0.85);
        params.setMaxContentLength(50000);

        String prompt = retriever.buildPromptForFindings("default", eightTextDocs(), params).toString();

        assertThat(prompt.length()).isLessThan(10_000);
    }
}
