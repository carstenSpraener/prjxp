package de.spraener.prjxp.gldrtrvr.text;

import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    void contentOverBudgetIsTruncatedWithNotice() {
        SearchParams tinyBudget = new SearchParams(20, 0.85);
        tinyBudget.setMaxContentLength(20);   // tiny budget
        PxChunk chunk = textChunk("big.rst:text", "docs/big.rst", "x".repeat(100), 0, 1);

        String prompt = retriever.buildPromptForFindings("default", List.of(new ScoredChunk(chunk, 0.9)), tinyBudget).toString();

        assertThat(prompt).doesNotContain("x".repeat(100));
        assertThat(prompt).contains("Groessenlimit");
    }
}
