package de.spraener.prjxp.gldrtrvr.md;

import de.spraener.prjxp.common.config.GraphRerankConfig;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingService;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 03b: VectorSearch-Doc-Windowing. Tests for the doc-chunk windowing in
 * {@link MarkdownRetriever#buildPromptForFindings} (the vectorSearch path).
 */
class MarkdownRetrieverTest {
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
    // A base strategy that always ranks 1.0 so that single chunks get a non-zero root rank
    // (a zero rank would make the session skip the chunk entirely).
    private final MarkdownRetriever retriever = new MarkdownRetriever(
            new PxChunkDaoProvider(List.of(dao)),
            new ChunkRankingService(List.of(new ChunkRankingStrategy() {
                @Override
                public boolean supports(PxChunk chunk) {
                    return true;
                }

                @Override
                public double rank(PxChunk chunk) {
                    return 1.0;
                }
            }), new GraphRerankConfig()));
    private final SearchParams sp = new SearchParams(20, 0.85);

    @BeforeEach
    void setUp() {
        ref.setProjectName("default");
        ref.setDefault(true);
        db.clear();
    }

    private PxChunk mdChunk(String id, String file, String content, String type) {
        return PxChunk.create(
                c -> c.setId(id),
                c -> c.setMimeType("text/markdown"),
                c -> c.setFile(file),
                c -> c.setContent(content),
                c -> c.setPart(0),
                c -> c.setTotal(1),
                c -> c.getMetadata().put(PxChunk.PXCHUNK_TYPE, type));
    }

    // 22 KB Markdown chunk (pxchunk_type neither FILE nor SECTION) -> windowed, within budget, marker present
    @Test
    void largeMarkdownChunkIsWindowedWithinBudget() {
        String content = "lorem ipsum dolor\n".repeat(1200);   // ~21.6 KB
        PxChunk chunk = mdChunk("big.md", "docs/big.md", content, "PARAGRAPH");
        db.put("big.md", List.of(chunk));

        String prompt = retriever.buildPromptForFindings("default", List.of(new ScoredChunk(chunk, 0.9)), sp).toString();

        assertThat(prompt.length())
                .as("windowed content + marker must fit docMaxChars + 200")
                .isLessThanOrEqualTo(2000 + 200);
        assertThat(prompt).contains("readFile(\"docs/big.md\")");
        assertThat(prompt).doesNotContain(content);   // the full 22 KB must not be in the prompt
    }

    // 1 KB Markdown chunk (< docMaxChars) -> unchanged, no marker
    @Test
    void smallMarkdownChunkIsUnchangedWithoutMarker() {
        String content = "x\n".repeat(500);                            // 1000 chars < 2000
        PxChunk chunk = mdChunk("small.md", "docs/small.md", content, "PARAGRAPH");
        db.put("small.md", List.of(chunk));

        String prompt = retriever.buildPromptForFindings("default", List.of(new ScoredChunk(chunk, 0.9)), sp).toString();

        assertThat(prompt).contains(content);
        assertThat(prompt).doesNotContain("readFile");
    }

    // FILE chunk (22 KB content) -> only the path line, content never in the prompt (existing behavior)
    @Test
    void fileChunkOnlyGetsPathLineContentNeverInPrompt() {
        String content = "lorem ipsum dolor\n".repeat(1200);           // ~21.6 KB
        PxChunk chunk = mdChunk("big.md", "docs/big.md", content, "FILE");
        db.put("big.md", List.of(chunk));

        String prompt = retriever.buildPromptForFindings("default", List.of(new ScoredChunk(chunk, 0.9)), sp).toString();

        assertThat(prompt).contains("In Datei: docs/big.md:");
        assertThat(prompt).doesNotContain("lorem ipsum");
    }

    // SECTION chunk -> title line, unchanged behavior
    @Test
    void sectionChunkGetsTitleLine() {
        PxChunk chunk = mdChunk("sec.md", "docs/big.md", "My Section Title", "SECTION");
        db.put("sec.md", List.of(chunk));

        String prompt = retriever.buildPromptForFindings("default", List.of(new ScoredChunk(chunk, 0.9)), sp).toString();

        assertThat(prompt).contains("## My Section Title");
    }

    // Non-markdown chunk -> modifyPromptByChunk returns "" (existing behavior)
    @Test
    void nonMarkdownChunkContributesNothing() {
        PxChunk chunk = PxChunk.create(
                c -> c.setId("plain.txt"),
                c -> c.setMimeType("text/plain"),
                c -> c.setFile("docs/plain.txt"),
                c -> c.setContent("some text"),
                c -> c.setPart(0),
                c -> c.setTotal(1));
        db.put("plain.txt", List.of(chunk));

        String prompt = retriever.buildPromptForFindings("default", List.of(new ScoredChunk(chunk, 0.9)), sp).toString();

        assertThat(prompt).isEmpty();
    }

    // retrieveSearchHits is a stub (existing behavior)
    @Test
    void retrieveSearchHitsReturnsEmptyList() {
        assertThat(retriever.retrieveSearchHits("default", List.of())).isEmpty();
    }

    // Missing parts are loaded from the store and combined (existing behavior)
    @Test
    void missingPartsAreLoadedFromStoreAndCombined() {
        PxChunk part0 = mdChunk("big.md", "docs/big.md", "first part\n", "PARAGRAPH");
        part0.setTotal(2);
        PxChunk part1 = mdChunk("big.md", "docs/big.md", "second part\n", "PARAGRAPH");
        part1.setPart(1);
        part1.setTotal(2);
        db.put("big.md", List.of(part0, part1));

        String prompt = retriever.buildPromptForFindings("default", List.of(new ScoredChunk(part0, 0.9)), sp).toString();

        assertThat(prompt).contains("first part").contains("second part");
    }

    // ---- Phase 03c: doc-specific content budget (vectorSearch path) ----

    /** 8 Markdown docs of ~2.2 KB each (all > docMaxChars, so each is windowed to ~2 KB). */
    private List<ScoredChunk> eightMarkdownDocs() {
        List<ScoredChunk> chunks = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            String content = "lorem ipsum dolor\n".repeat(123);   // 2214 chars > docMaxChars (2000)
            PxChunk chunk = mdChunk("doc" + i + ".md", "docs/doc" + i + ".md", content, "PARAGRAPH");
            db.put("doc" + i + ".md", List.of(chunk));
            chunks.add(new ScoredChunk(chunk, 0.9));
        }
        return chunks;
    }

    // 8 windowed docs with the 50 KB code budget -> the 8 KB doc cap applies, not the code budget
    @Test
    void docBudgetCapsTotalDocVolume() {
        SearchParams params = new SearchParams(20, 0.85);
        params.setMaxContentLength(50000);

        String prompt = retriever.buildPromptForFindings("default", eightMarkdownDocs(), params).toString();

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

        String prompt = retriever.buildPromptForFindings("default", eightMarkdownDocs(), params).toString();

        assertThat(prompt.length()).isLessThanOrEqualTo(5000 + 200);
        assertThat(prompt).contains("Groessenlimit");
    }

    // 8 x 2.2 KB docs -> total prompt < 10 KB (was ~18 KB before the doc budget)
    @Test
    void eightDocsStayBelowTenKb() {
        SearchParams params = new SearchParams(20, 0.85);
        params.setMaxContentLength(50000);

        String prompt = retriever.buildPromptForFindings("default", eightMarkdownDocs(), params).toString();

        assertThat(prompt.length()).isLessThan(10_000);
    }
}
