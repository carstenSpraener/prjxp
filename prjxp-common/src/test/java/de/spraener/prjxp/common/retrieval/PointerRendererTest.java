package de.spraener.prjxp.common.retrieval;

import de.spraener.prjxp.common.graph.SignatureMetadata;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PointerRendererTest {

    private final PointerRenderer renderer = new PointerRenderer();

    // ---- Efficiency measure 1: NAMES (filenames-only) enumeration ----

    @Test
    void renderNamesListsDistinctFilesInFirstSeenOrder() {
        String out = PointerRenderer.renderNames(List.of(
                new ScoredChunk(chunk("a", "src/A.java", null), 0.9),
                new ScoredChunk(chunk("b", "src/B.java", null), 0.8),
                new ScoredChunk(chunk("a2", "src/A.java", null), 0.7)));   // duplicate file

        assertThat(out).isEqualTo("src/A.java\nsrc/B.java");
    }

    @Test
    void renderNamesUsesParentForFilelessDependencyChunk() {
        PxChunk dependency = chunk("de.ex.Foo.dependencies", null, null);
        dependency.setParent("de.ex.Foo");   // real dependency chunks carry the parent type, no file

        String out = PointerRenderer.renderNames(List.of(
                new ScoredChunk(dependency, 0.9),
                new ScoredChunk(chunk("x", "src/X.java", null), 0.8)));

        assertThat(out).isEqualTo("de.ex.Foo\nsrc/X.java");
    }

    @Test
    void renderNamesOmitsChunksWithoutFileOrParent() {
        String out = PointerRenderer.renderNames(List.of(
                new ScoredChunk(chunk("orphan", null, null), 0.9),
                new ScoredChunk(chunk("blank", "   ", null), 0.8),
                new ScoredChunk(chunk("real", "src/Real.java", null), 0.7)));

        assertThat(out).isEqualTo("src/Real.java");
    }

    @Test
    void renderNamesEmptyForEmptyInput() {
        assertThat(PointerRenderer.renderNames(List.of())).isEmpty();
    }

    @Test
    void docChunkFullBlock() {
        PxChunk chunk = chunk("docs/a.md:3:07", "docs/a.md", Map.of(
                "hierarchy", "Architektur > Persistence",
                "semantic_cluster_id", "s14",
                "semantic_node_id", "NODE1",
                "keywords", "jta, hikari",
                "cluster_keywords", "jta, pool"));

        String block = renderer.render("prjxp", chunk, 0.84);

        assertThat(block).isEqualTo("""
                ## docs/a.md
                project_id: "prjxp"
                chunk_id: "docs/a.md:3:07"
                score: 0.84
                file: "docs/a.md"
                chapter: "Architektur > Persistence"
                cluster_id: "s14"
                cluster_keywords: ["jta", "pool"]
                keywords: ["jta", "hikari"]
                → readFile("docs/a.md")
                → semanticGraphNode("NODE1")
                """);
    }

    @Test
    void codeMethodChunk() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("graph_node", "de.ex.Foo#bar(java.lang.String)");
        metadata.put("leiden_cluster_id", "c7");
        metadata.put("ast_neighbours", "de.ex.Foo" + SignatureMetadata.LIST_SEPARATOR + "de.ex.Bar#baz()");
        PxChunk chunk = chunk("de.ex.Foo.bar(int, String)", "src/de/ex/Foo.java", metadata);

        String block = renderer.render("prjxp", chunk, 0.91);

        assertThat(block).isEqualTo("""
                ## src/de/ex/Foo.java
                project_id: "prjxp"
                chunk_id: "de.ex.Foo.bar(int, String)"
                score: 0.91
                file: "src/de/ex/Foo.java"
                chapter: "de.ex.Foo#bar(java.lang.String)"
                cluster_id: "c7"
                neighbours: ["de.ex.Foo", "de.ex.Bar#baz()"]
                → readFile("src/de/ex/Foo.java")
                → readBySignature("bar", "de.ex.Foo")
                → graphNode("de.ex.Foo")
                """);
    }

    @Test
    void codeClassFrame() {
        PxChunk chunk = chunk("de.ex.Foo", "src/de/ex/Foo.java",
                Map.of("graph_node", "de.ex.Foo"));

        String block = renderer.render("prjxp", chunk, 0.5);

        assertThat(block).contains("→ readBySignature(\"de.ex.Foo\")");
        assertThat(block).contains("→ graphNode(\"de.ex.Foo\")");
        assertThat(block).doesNotContain("readBySignature(\"de.ex.Foo\", ");
    }

    @Test
    void titleFallsBackToGraphNodeThenId() {
        PxChunk codeOnly = chunk("legacy-code-id", null, Map.of("graph_node", "de.ex.Foo"));
        assertThat(renderer.render("prjxp", codeOnly, 0.4)).startsWith("## de.ex.Foo\n");

        PxChunk idOnly = chunk("plain-id", null, null);
        assertThat(renderer.render("prjxp", idOnly, 0.4)).startsWith("## plain-id\n");
    }

    @Test
    void missingFieldsOmitted() {
        PxChunk chunk = chunk("plain-id", null, null);

        String block = renderer.render("prjxp", chunk, 0.42);

        assertThat(block).isEqualTo("""
                ## plain-id
                project_id: "prjxp"
                chunk_id: "plain-id"
                score: 0.42
                """);
        assertThat(block).doesNotContain("file:");
        assertThat(block).doesNotContain("chapter:");
        assertThat(block).doesNotContain("cluster_id:");
        assertThat(block).doesNotContain("keywords:");
        assertThat(block).doesNotContain("neighbours:");
        assertThat(block).doesNotContain("→");
    }

    @Test
    void neighbourIdsWithCommasAreKeptAsOneEntry() {
        String methodId = "de.ex.Foo.bar(int, String)";
        PxChunk chunk = chunk("de.ex.Foo.bar", "src/de/ex/Foo.java", Map.of(
                "ast_neighbours", methodId + SignatureMetadata.LIST_SEPARATOR + "de.ex.Baz"));

        String block = renderer.render("prjxp", chunk, 0.5);

        assertThat(block).contains("neighbours: [\"" + methodId + "\", \"de.ex.Baz\"]");
    }

    @Test
    void blankValuesAreTreatedAsMissing() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("hierarchy", "   ");
        metadata.put("keywords", " , ");
        metadata.put("semantic_node_id", "");
        PxChunk chunk = chunk("blanky", "   ", metadata);

        String block = renderer.render(null, chunk, 0.1);

        assertThat(block).isEqualTo("""
                ## blanky
                project_id: ""
                chunk_id: "blanky"
                score: 0.10
                """);
    }

    @Test
    void neighboursMergeDedupCap() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("ast_neighbours", String.join(SignatureMetadata.LIST_SEPARATOR, "a", "b", "a"));
        metadata.put("semantic_neighbours", String.join(SignatureMetadata.LIST_SEPARATOR,
                "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"));
        PxChunk chunk = chunk("n1", "n.md", metadata);

        String block = renderer.render("prjxp", chunk, 0.5);

        assertThat(block).contains(
                "neighbours: [\"a\", \"b\", \"c\", \"d\", \"e\", \"f\", \"g\", \"h\", \"i\", \"j\"]\n");
    }

    @Test
    void keywordsCapAndDedup() {
        PxChunk chunk = chunk("k1", "k.md",
                Map.of("keywords", "x, x, y, z, w, v, u, t, s, r"));

        String block = renderer.render("prjxp", chunk, 0.5);

        assertThat(block).contains("keywords: [\"x\", \"y\", \"z\", \"w\", \"v\", \"u\", \"t\", \"s\"]\n");
    }

    @Test
    void clusterIdPrecedence() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("leiden_cluster_id", "c7");
        metadata.put("semantic_cluster_id", "s3");
        PxChunk chunk = chunk("c1", "c.java", metadata);

        String block = renderer.render("prjxp", chunk, 0.5);

        assertThat(block).contains("cluster_id: \"c7\"\n");
        assertThat(block).doesNotContain("s3");
    }

    @Test
    void fallbackGraphNodeUsedOnlyWhenMetadataIsMissing() {
        PxChunk dependency = chunk("de.ex.Foo.dependencies", null, null);

        String block = renderer.render("prjxp", dependency, 0.5, "de.ex.Foo");

        assertThat(block).contains("chapter: \"de.ex.Foo\"\n")
                .contains("→ readBySignature(\"de.ex.Foo\")")
                .contains("→ graphNode(\"de.ex.Foo\")")
                .doesNotContain("readFile");

        PxChunk ownNode = chunk("de.ex.Foo#bar()", "Foo.java", Map.of("graph_node", "de.ex.Foo#bar()"));
        assertThat(renderer.render("prjxp", ownNode, 0.5, "de.ex.Other"))
                .contains("→ graphNode(\"de.ex.Foo\")")
                .doesNotContain("de.ex.Other");
    }

    @Test
    void blankFallbackGraphNodeIsIgnored() {
        PxChunk dependency = chunk("de.ex.Foo.dependencies", null, null);

        String block = renderer.render("prjxp", dependency, 0.5, "   ");

        assertThat(block).doesNotContain("chapter:")
                .doesNotContain("→");
    }

    private static PxChunk chunk(String id, String file, Map<String, String> metadata) {
        return PxChunk.create(chunk -> {
            chunk.setId(id);
            chunk.setFile(file);
            if (metadata != null) {
                chunk.getMetadata().putAll(metadata);
            }
        });
    }
}
