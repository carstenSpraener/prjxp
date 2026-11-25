package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.EdgeType;
import de.spraener.prjxp.common.model.FileListResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResultRenderersTest {

    @Test
    void graphSearchRendersHeaderSeedsNodesAndEdges() {
        GraphSearchView view = new GraphSearchView("demo", List.of("demo.PxChunk"),
                List.of(new GraphSearchView.GraphSearchNode("demo.PxChunk", "src/PxChunk.java", false, "c0", 1.0),
                        new GraphSearchView.GraphSearchNode("demo.PxChunk#create()", "src/PxChunk.java", true, "c0", 0.4)),
                List.of(new GraphSearchView.GraphSearchEdge("demo.PxChunk", "demo.PxChunk#create()",
                        EdgeType.CONTAINS, false)),
                null, 2, 1, false);

        String text = ResultRenderers.renderGraphSearch(view, 2000);

        assertThat(text).contains("Seeds: demo.PxChunk");
        assertThat(text).contains("2 nodes, 1 edges");
        assertThat(text).contains("demo.PxChunk#create()");
        assertThat(text).contains("CONTAINS");
        assertThat(text).doesNotContain("gekürzt");
    }

    @Test
    void graphSearchRendersErrorAndTruncationHint() {
        GraphSearchView error = GraphSearchView.unavailable("Graph nicht verfügbar");
        assertThat(ResultRenderers.renderGraphSearch(error, 2000)).contains("ERROR");

        GraphSearchView truncated = new GraphSearchView("demo", List.of("demo.A"),
                List.of(new GraphSearchView.GraphSearchNode("demo.A", "src/A.java", false, null, null)),
                List.of(), null, 10, 20, true);
        assertThat(ResultRenderers.renderGraphSearch(truncated, 2000)).contains("gekürzt");
    }

    @Test
    void configCatalogRendersOneLinePerProperty() {
        ConfigCatalog catalog = new ConfigCatalog("demo", List.of(
                new ConfigProperty("prjxp.a.b", "String", "x", "Beschreibung", "application.yaml"),
                new ConfigProperty("prjxp.c", "int", null, null, "Demo.java")), null);

        String text = ResultRenderers.renderConfigCatalog(catalog, 2000);

        assertThat(text).contains("Config: demo (2 properties)");
        assertThat(text).contains("prjxp.a.b (String) default=x — Beschreibung");
        assertThat(text).contains("[application.yaml]");
        assertThat(text).contains("prjxp.c (int)");
        assertThat(text).contains("[Demo.java]");
    }

    @Test
    void configCatalogRendersEmptyAndError() {
        assertThat(ResultRenderers.renderConfigCatalog(new ConfigCatalog("demo", List.of(), null), 2000))
                .contains("No configuration properties");
        assertThat(ResultRenderers.renderConfigCatalog(ConfigCatalog.unavailable("nope"), 2000))
                .contains("ERROR: nope");
    }

    @Test
    void fileListRendersDirectoriesEntriesAndTruncationHint() {
        String dirs = ResultRenderers.renderFileList(
                new FileListResult(List.of("docs/", "src/"), false));
        assertThat(dirs).startsWith("Found 2 director");
        assertThat(dirs).contains("  - docs/");

        String mixed = ResultRenderers.renderFileList(
                new FileListResult(List.of("pom.xml", "src/"), false));
        assertThat(mixed).startsWith("Found 2 entries");

        String truncated = ResultRenderers.renderFileList(
                new FileListResult(List.of("src/a/Foo.java"), true));
        assertThat(truncated).contains("truncated");
        assertThat(truncated).contains("('dir/')");
    }
}
