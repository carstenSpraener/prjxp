package de.spraener.prjxp.gldrtrvr;

import de.spraener.prjxp.common.config.GraphRerankConfig;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingService;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingStrategy;
import de.spraener.prjxp.gldrtrvr.code.java.JavaRetriever;
import de.spraener.prjxp.gldrtrvr.md.MarkdownRetriever;
import de.spraener.prjxp.gldrtrvr.text.TextRetriever;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 06: the {@code POINTERS} level of all retrievers renders the {@code PointerRenderer} block
 * (Phase 02) instead of the legacy pointer lines. FULL/SKELETON stay unchanged.
 */
class PointerRendererIntegrationTest {

    private static final String PROJECT = "prjxp";

    // ------------------------------------------------------------------
    // Markdown
    // ------------------------------------------------------------------

    @Test
    void markdownPointersRendersFullBlock() {
        PxChunk chunk = chunk("docs/a.md:1", "text/markdown", "docs/a.md", "BODY-MARKER", Map.of(
                "hierarchy", "Architektur > Persistence",
                "semantic_cluster_id", "s14",
                "semantic_node_id", "NODE-1",
                "keywords", "jta, hikari",
                "cluster_keywords", "jta, pool",
                "semantic_neighbours", String.join(
                        de.spraener.prjxp.common.graph.SignatureMetadata.LIST_SEPARATOR, "n1", "n2")));

        String prompt = markdownRetriever(chunk).buildPromptForFindings(
                PROJECT, List.of(new ScoredChunk(chunk, 0.9)), pointers()).toString();

        assertThat(prompt).isEqualTo("""
                ## docs/a.md
                project_id: "prjxp"
                chunk_id: "docs/a.md:1"
                score: 0.90
                file: "docs/a.md"
                chapter: "Architektur > Persistence"
                cluster_id: "s14"
                cluster_keywords: ["jta", "pool"]
                keywords: ["jta", "hikari"]
                neighbours: ["n1", "n2"]
                → readFile("docs/a.md")
                → semanticGraphNode("NODE-1")
                """);
        assertThat(prompt).doesNotContain("BODY-MARKER");
    }

    @Test
    void markdownDedupPerFile() {
        PxChunk first = chunk("docs/a.md:1", "text/markdown", "docs/a.md", "one", Map.of());
        PxChunk second = chunk("docs/a.md:2", "text/markdown", "docs/a.md", "two", Map.of());

        String prompt = markdownRetriever(first, second).buildPromptForFindings(
                PROJECT, List.of(new ScoredChunk(first, 0.9), new ScoredChunk(second, 0.8)), pointers()).toString();

        assertThat(countOccurrences(prompt, "→ readFile(\"docs/a.md\")")).isEqualTo(1);
        assertThat(countOccurrences(prompt, "## docs/a.md\n")).isEqualTo(1);
    }

    @Test
    void markdownChunkWithoutFileIsIgnored() {
        PxChunk withFile = chunk("docs/a.md:1", "text/markdown", "docs/a.md", "body", Map.of());
        PxChunk withoutFile = chunk("orphan:1", "text/markdown", null, "orphan body", Map.of());

        String prompt = markdownRetriever(withFile, withoutFile).buildPromptForFindings(
                PROJECT, List.of(new ScoredChunk(withoutFile, 0.95), new ScoredChunk(withFile, 0.9)),
                pointers()).toString();

        assertThat(prompt).contains("→ readFile(\"docs/a.md\")")
                .doesNotContain("orphan");
    }

    @Test
    void markdownBudgetEmitsSkipMarker() {
        PxChunk chunk = chunk("docs/a.md:1", "text/markdown", "docs/a.md", "body", Map.of());
        SearchParams params = pointers();
        params.setMaxContentLength(10);

        String prompt = markdownRetriever(chunk).buildPromptForFindings(
                PROJECT, List.of(new ScoredChunk(chunk, 0.9)), params).toString();

        assertThat(prompt).contains("[weitere 1 Doku-Dateien wegen Groessenlimit nicht enthalten]");
    }

    // ------------------------------------------------------------------
    // Java
    // ------------------------------------------------------------------

    @Test
    void javaPointersBypassesSession() {
        PxChunk chunk = chunk("de.ex.Foo", "text/x-java-code", "src/Foo.java", "class Foo {}",
                Map.of("java_code_section", "classFrame",
                        "graph_node", "de.ex.Foo",
                        "leiden_cluster_id", "c7",
                        "ast_neighbours", String.join(
                                de.spraener.prjxp.common.graph.SignatureMetadata.LIST_SEPARATOR,
                                "de.ex.Bar", "de.ex.Baz"),
                        "keywords", "foo, bar"));

        String prompt = javaRetriever(chunk).buildPromptForFindings(
                PROJECT, List.of(new ScoredChunk(chunk, 0.91)), pointers()).toString();

        assertThat(prompt).isEqualTo("""
                ## src/Foo.java
                project_id: "prjxp"
                chunk_id: "de.ex.Foo"
                score: 0.91
                file: "src/Foo.java"
                chapter: "de.ex.Foo"
                cluster_id: "c7"
                keywords: ["foo", "bar"]
                neighbours: ["de.ex.Bar", "de.ex.Baz"]
                → readFile("src/Foo.java")
                → readBySignature("de.ex.Foo")
                → graphNode("de.ex.Foo")
                """);
        // no session tree, no JavaPromptModifier rendering
        assertThat(prompt).doesNotContain("Hier ein Rumpf der Klasse")
                .doesNotContain("```java");
    }

    @Test
    void javaDependencyPointerUsesParentGraphNode() {
        PxChunk dependencies = chunk("de.ex.Foo.dependencies", "text/plain", null, "de.ex.Bar",
                Map.of("java_code_section", "dependenciesInfo"));
        dependencies.setParent("de.ex.Foo");

        String prompt = javaRetriever(dependencies).buildPromptForFindings(
                PROJECT, List.of(new ScoredChunk(dependencies, 0.7)), pointers()).toString();

        assertThat(prompt).contains("chapter: \"de.ex.Foo\"")
                .contains("→ graphNode(\"de.ex.Foo\")")
                .doesNotContain("de.ex.Bar");
    }

    @Test
    void javaDependencyPointerIsEmittedOncePerType() {
        PxChunk first = chunk("de.ex.Foo.dependencies", "text/plain", null, "de.ex.Bar",
                Map.of("java_code_section", "dependenciesInfo"));
        first.setParent("de.ex.Foo");
        PxChunk second = chunk("de.ex.Foo.dependencies:2", "text/plain", null, "de.ex.Baz",
                Map.of("java_code_section", "dependenciesInfo"));
        second.setParent("de.ex.Foo");

        String prompt = javaRetriever(first, second).buildPromptForFindings(
                PROJECT, List.of(new ScoredChunk(first, 0.9), new ScoredChunk(second, 0.8)),
                pointers()).toString();

        assertThat(countOccurrences(prompt, "→ graphNode(\"de.ex.Foo\")")).isEqualTo(1);
        assertThat(countOccurrences(prompt, "chunk_id:")).isEqualTo(1);
    }

    @Test
    void javaSkeletonUnchanged() {
        PxChunk chunk = chunk("de.ex.Foo", "text/x-java-code", "src/Foo.java", "class Foo { int x; }",
                Map.of("java_code_section", "classFrame",
                        "graph_node", "de.ex.Foo",
                        "leiden_weight", "1.0",
                        "keywords", "foo"));

        String prompt = javaRetriever(chunk).buildPromptForFindings(
                PROJECT, List.of(new ScoredChunk(chunk, 0.91)), skeleton()).toString();

        assertThat(prompt).contains("Hier ein Rumpf der Klasse de.ex.Foo")
                .contains("class Foo { int x; }")
                .doesNotContain("project_id:");
    }

    @Test
    void javaPointersRespectsBudget() {
        PxChunk chunk = chunk("de.ex.Foo", "text/x-java-code", "src/Foo.java", "class Foo {}",
                Map.of("java_code_section", "classFrame", "graph_node", "de.ex.Foo"));
        SearchParams params = pointers();
        params.setMaxContentLength(10);

        String prompt = javaRetriever(chunk).buildPromptForFindings(
                PROJECT, List.of(new ScoredChunk(chunk, 0.91)), params).toString();

        assertThat(prompt).contains("[weitere 1 Klassen wegen Groessenlimit nicht enthalten]");
    }

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    @Test
    void textPointersRendersFullBlock() {
        PxChunk chunk = chunk("docs/a.txt:1", TextRetriever.MIME, "docs/a.txt", "BODY-MARKER", Map.of(
                "hierarchy", "Notes",
                "semantic_cluster_id", "s2",
                "semantic_node_id", "NODE-2"));

        String prompt = new TextRetriever(new PxChunkDaoProvider(List.of(dao(chunk))))
                .buildPromptForFindings(PROJECT, List.of(new ScoredChunk(chunk, 0.9)), pointers()).toString();

        assertThat(prompt).isEqualTo("""
                ## docs/a.txt
                project_id: "prjxp"
                chunk_id: "docs/a.txt:1"
                score: 0.90
                file: "docs/a.txt"
                chapter: "Notes"
                cluster_id: "s2"
                → readFile("docs/a.txt")
                → semanticGraphNode("NODE-2")
                """);
        assertThat(prompt).doesNotContain("BODY-MARKER");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private MarkdownRetriever markdownRetriever(PxChunk... chunks) {
        ChunkRankingStrategy ranking = new ChunkRankingStrategy() {
            @Override
            public boolean supports(PxChunk candidate) {
                return true;
            }

            @Override
            public double rank(PxChunk candidate) {
                return 1.0;
            }
        };
        return new MarkdownRetriever(new PxChunkDaoProvider(List.of(dao(chunks))),
                new ChunkRankingService(List.of(ranking), new GraphRerankConfig()));
    }

    private JavaRetriever javaRetriever(PxChunk... chunks) {
        ChunkRankingStrategy ranking = new ChunkRankingStrategy() {
            @Override
            public boolean supports(PxChunk candidate) {
                return true;
            }

            @Override
            public double rank(PxChunk candidate) {
                return 1.0;
            }
        };
        return new JavaRetriever(new PxChunkDaoProvider(List.of(dao(chunks))),
                new ChunkRankingService(List.of(ranking), new GraphRerankConfig()));
    }

    private PxChunkDao dao(PxChunk... chunks) {
        PrjXPEmbeddingStoreReference reference = new PrjXPEmbeddingStoreReference();
        reference.setProjectName(PROJECT);
        reference.setDefault(true);
        Map<String, List<PxChunk>> byId = new HashMap<>();
        for (PxChunk chunk : chunks) {
            byId.computeIfAbsent(chunk.getId(), key -> new ArrayList<>()).add(chunk);
        }
        return new PxChunkDao() {
            @Override
            public PrjXPEmbeddingStoreReference getStoreReference() {
                return reference;
            }

            @Override
            public List<PxChunk> findById(String id) {
                // mutable copy: PxChunk.combine sorts the list in place
                return new ArrayList<>(byId.getOrDefault(id, List.of()));
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
                return byId.values().stream().flatMap(List::stream);
            }
        };
    }

    private PxChunk chunk(String id, String mimeType, String file, String content, Map<String, String> metadata) {
        return PxChunk.create(
                chunk -> chunk.setId(id),
                chunk -> chunk.setMimeType(mimeType),
                chunk -> chunk.setFile(file),
                chunk -> chunk.setPart(0),
                chunk -> chunk.setTotal(1),
                chunk -> chunk.setContent(content),
                chunk -> chunk.getMetadata().putAll(metadata));
    }

    private SearchParams pointers() {
        SearchParams params = new SearchParams(20, 0.85);
        params.setContentLevel(ContentLevel.POINTERS);
        return params;
    }

    private SearchParams skeleton() {
        SearchParams params = new SearchParams(20, 0.85);
        params.setContentLevel(ContentLevel.SKELETON);
        return params;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}