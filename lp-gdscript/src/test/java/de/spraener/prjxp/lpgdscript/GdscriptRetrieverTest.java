package de.spraener.prjxp.lpgdscript;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.retrieval.SearchParams;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class GdscriptRetrieverTest {

    // ------------------------------------------------------------------
    // Fixtures (§5.1: a Player-like file)
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked") // PxChunk.create(Consumer<PxChunk>...) varargs; a single consumer is always passed
    private static PxChunk gd(String id, String section, String parent, String content) {
        return PxChunk.create(c -> {
            c.setId(id);
            c.setParent(parent);
            c.setMimeType(GdscriptCodeChunker.GDSCRIPT_CODE_MIME_TYPE);
            c.setPart(0);
            c.setTotal(1);
            c.setContent(content);
            c.getMetadata().put(GdscriptCodeSection.METADATA_KEY, section);
        });
    }

    @SuppressWarnings("unchecked") // PxChunk.create(Consumer<PxChunk>...) varargs; a single consumer is always passed
    private static PxChunk part(String id, String section, String parent, int part, int total, String content) {
        return PxChunk.create(c -> {
            c.setId(id);
            c.setParent(parent);
            c.setMimeType(GdscriptCodeChunker.GDSCRIPT_CODE_MIME_TYPE);
            c.setPart(part);
            c.setTotal(total);
            c.setOverlap(0);
            c.setContent(content);
            c.getMetadata().put(GdscriptCodeSection.METADATA_KEY, section);
        });
    }

    private static PxChunk playerHeader() {
        return gd("Player", "header", null,
                "class_name Player\nextends CharacterBody2D\n## A player that moves.\n");
    }

    private static PxChunk playerFields() {
        return gd("Player.fields", "fields", "Player",
                "@export var speed = 300\nvar health = 100\n");
    }

    private static PxChunk playerMove() {
        return gd("Player.move(dir)", "func", "Player",
                "func move(dir):\n\tposition += dir * speed\n");
    }

    private static PxChunk playerMoveDoc() {
        return gd("Player.move(dir).doc", "funcDoc", "Player.move(dir)",
                "## Moves the player.\n");
    }

    private static PxChunk playerReady() {
        return gd("Player._ready()", "func", "Player",
                "func _ready():\n\tprint(\"ready\")\n");
    }

    private static InMemoryChunkDao fullDao() {
        InMemoryChunkDao dao = new InMemoryChunkDao();
        dao.put("Player", playerHeader());
        dao.put("Player.fields", playerFields());
        dao.put("Player.move(dir)", playerMove());
        dao.put("Player.move(dir).doc", playerMoveDoc());
        dao.put("Player._ready()", playerReady());
        return dao;
    }

    private static GdscriptRetriever retriever(InMemoryChunkDao dao) {
        return new GdscriptRetriever(new TestChunkDaoProvider(dao));
    }

    private static SearchParams params(int maxResult, int maxContentLength) {
        SearchParams p = new SearchParams(maxResult, 0.0);
        p.setMaxContentLength(maxContentLength);
        return p;
    }

    private static long countSections(String text) {
        long n = 0;
        int i = 0;
        while ((i = text.indexOf("=== ", i)) >= 0) {
            n++;
            i += 4;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Filtering / grouping / combining
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked") // PxChunk.create(Consumer<PxChunk>...) varargs; a single consumer is always passed
    void filtersNonGdscriptChunks() {
        PxChunk javaChunk = PxChunk.create(c -> {
            c.setId("com/example/Foo");
            c.setMimeType("text/x-java");
            c.setContent("class Foo {}\n");
        });
        List<ScoredChunk> chunks = List.of(
                new ScoredChunk(playerHeader(), 0.9),
                new ScoredChunk(playerMove(), 0.8),
                new ScoredChunk(javaChunk, 0.95));
        GdscriptRetriever r = retriever(fullDao());

        StringBuilder prompt = r.buildPromptForFindings("proj", chunks, params(8, 50000));
        assertThat(prompt.toString()).doesNotContain("class Foo");

        List<SearchHit> hits = r.retrieveSearchHits("proj", chunks);
        assertThat(hits).extracting(SearchHit::chunkId).doesNotContain("com/example/Foo");
        assertThat(hits).hasSize(2);
    }

    @Test
    void groupsById_bestScore() {
        PxChunk high = gd("Player", "header", null, "class_name Player\nHIGH\n");
        PxChunk low = gd("Player", "header", null, "class_name Player\nLOW\n");
        List<ScoredChunk> chunks = List.of(new ScoredChunk(low, 0.7), new ScoredChunk(high, 0.9));
        GdscriptRetriever r = retriever(fullDao());

        StringBuilder prompt = r.buildPromptForFindings("proj", chunks, params(8, 50000));
        assertThat(countSections(prompt.toString())).isEqualTo(1);
        assertThat(prompt.toString()).contains("HIGH").doesNotContain("LOW");

        List<SearchHit> hits = r.retrieveSearchHits("proj", chunks);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).score()).isEqualTo(0.9);
        assertThat(hits.get(0).snippet()).contains("HIGH").doesNotContain("LOW");
    }

    @Test
    void combinesPartsFromHitSet() {
        PxChunk p0 = part("Player.move(dir)", "func", "Player", 0, 2, "func move(dir):\n\t");
        PxChunk p1 = part("Player.move(dir)", "func", "Player", 1, 2, "position += dir * speed\n");
        List<ScoredChunk> chunks = List.of(new ScoredChunk(p0, 0.9), new ScoredChunk(p1, 0.8));
        GdscriptRetriever r = retriever(fullDao());

        List<SearchHit> hits = r.retrieveSearchHits("proj", chunks);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).chunkId()).isEqualTo("Player.move(dir)");
        assertThat(hits.get(0).snippet()).contains("func move(dir):");
        assertThat(hits.get(0).snippet()).contains("position += dir * speed");
    }

    @Test
    void combinesPartsViaDao_whenMissing() {
        PxChunk p0 = part("Player.move(dir)", "func", "Player", 0, 2, "func move(dir):\n\t");
        PxChunk p1 = part("Player.move(dir)", "func", "Player", 1, 2, "position += dir * speed\n");
        InMemoryChunkDao dao = new InMemoryChunkDao();
        dao.put("Player.move(dir)", p0, p1);
        dao.put("Player", playerHeader());
        dao.put("Player.move(dir).doc", playerMoveDoc());
        GdscriptRetriever r = retriever(dao);

        // only part 0 in the input; part 1 must be fetched via dao.findById
        List<ScoredChunk> chunks = List.of(new ScoredChunk(p0, 0.9));
        List<SearchHit> hits = r.retrieveSearchHits("proj", chunks);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).snippet()).contains("func move(dir):");
        assertThat(hits.get(0).snippet()).contains("position += dir * speed");
    }

    // ------------------------------------------------------------------
    // Enrichment
    // ------------------------------------------------------------------

    @Test
    void enrichesWithParentAndFuncDoc() {
        GdscriptRetriever r = retriever(fullDao());
        List<ScoredChunk> chunks = List.of(new ScoredChunk(playerMove(), 0.9));

        String text = r.buildPromptForFindings("proj", chunks, params(8, 50000)).toString();
        int marker = text.indexOf("# enclosing class: Player");
        int header = text.indexOf("class_name Player");
        int doc = text.indexOf("## Moves the player.");
        int func = text.indexOf("func move(dir):");
        assertThat(marker).as("parent marker").isGreaterThanOrEqualTo(0);
        assertThat(marker).isLessThan(header);
        assertThat(header).isLessThan(doc);
        assertThat(doc).isLessThan(func);

        List<SearchHit> hits = r.retrieveSearchHits("proj", chunks);
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).source()).isEqualTo("gdscript");
        assertThat(hits.get(0).snippet()).isNotNull();
    }

    @Test
    void skipsEnrichment_whenParentOrDocIsItselfAHit() {
        GdscriptRetriever r = retriever(fullDao());
        List<ScoredChunk> chunks = List.of(
                new ScoredChunk(playerMove(), 0.9),
                new ScoredChunk(playerHeader(), 0.85));

        List<SearchHit> hits = r.retrieveSearchHits("proj", chunks);
        SearchHit funcHit = hits.stream()
                .filter(h -> "Player.move(dir)".equals(h.chunkId()))
                .findFirst().orElseThrow();
        assertThat(funcHit.snippet()).doesNotContain("# enclosing class:");
        assertThat(funcHit.snippet()).doesNotContain("class_name Player");

        // the parent is rendered as its own section instead
        String text = r.buildPromptForFindings("proj", chunks, params(8, 50000)).toString();
        assertThat(text).contains("=== Player [header] ===");
    }

    @Test
    void selfContained_noDao_noEnrichment() {
        GdscriptRetriever r = new GdscriptRetriever();
        List<ScoredChunk> chunks = List.of(new ScoredChunk(playerMove(), 0.9));

        String text = r.buildPromptForFindings("proj", chunks, params(8, 50000)).toString();
        assertThat(text).doesNotContain("# enclosing class:");
        assertThat(text).doesNotContain("## Moves the player.");
        assertThat(text).contains("func move(dir):");

        List<SearchHit> hits = r.retrieveSearchHits("proj", chunks);
        assertThat(hits.get(0).snippet()).isEqualTo("func move(dir):\n\tposition += dir * speed\n");
    }

    // ------------------------------------------------------------------
    // Budget / cap / format / validators
    // ------------------------------------------------------------------

    @Test
    void respectsBudget() {
        GdscriptRetriever r = retriever(fullDao());
        List<ScoredChunk> chunks = List.of(
                new ScoredChunk(playerHeader(), 0.95),
                new ScoredChunk(playerFields(), 0.9),
                new ScoredChunk(playerMove(), 0.85),
                new ScoredChunk(playerReady(), 0.8));

        StringBuilder prompt = r.buildPromptForFindings("proj", chunks, params(8, 100));
        assertThat(prompt.length()).isLessThanOrEqualTo(100);
        assertThat(countSections(prompt.toString())).isLessThan(4);
    }

    @Test
    void capsAtMaxResult() {
        GdscriptRetriever r = retriever(fullDao());
        List<ScoredChunk> chunks = List.of(
                new ScoredChunk(playerHeader(), 0.95),
                new ScoredChunk(playerFields(), 0.9),
                new ScoredChunk(playerMove(), 0.85),
                new ScoredChunk(playerReady(), 0.8),
                new ScoredChunk(gd("Player.misc.9", "misc", "Player", "x = 1\n"), 0.75));

        StringBuilder prompt = r.buildPromptForFindings("proj", chunks, params(2, 50000));
        assertThat(countSections(prompt.toString())).isEqualTo(2);
    }

    @Test
    void promptFormat() {
        // empty dao: no enrichment, so the block is exactly header + content + blank lines
        GdscriptRetriever r = retriever(new InMemoryChunkDao());
        List<ScoredChunk> chunks = List.of(new ScoredChunk(playerReady(), 0.9));

        assertThat(r.buildPromptForFindings("proj", chunks, params(8, 50000)).toString())
                .isEqualTo("=== Player._ready() [func] ===\nfunc _ready():\n\tprint(\"ready\")\n\n\n");
    }

    @Test
    void validatorRejects_promptEmpty() {
        GdscriptRetriever r = retriever(fullDao());
        List<ScoredChunk> chunks = List.of(new ScoredChunk(playerMove(), 0.9));

        Function<String, Boolean> reject = text -> false;
        assertThat(r.buildPromptForFindings("proj", chunks, params(8, 50000), reject)).isEmpty();
    }

    @Test
    void validatorAccepts_promptKept() {
        GdscriptRetriever r = retriever(fullDao());
        List<ScoredChunk> chunks = List.of(new ScoredChunk(playerMove(), 0.9));

        Function<String, Boolean> accept = text -> true;
        assertThat(r.buildPromptForFindings("proj", chunks, params(8, 50000), accept)).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked") // raw Function[] to pass a single null validator element
    void nullValidatorIgnored() {
        GdscriptRetriever r = retriever(fullDao());
        List<ScoredChunk> chunks = List.of(new ScoredChunk(playerMove(), 0.9));

        Function<String, Boolean> nullValidator = null;
        assertThat(r.buildPromptForFindings("proj", chunks, params(8, 50000),
                new Function[]{nullValidator})).isNotEmpty();
    }
}
