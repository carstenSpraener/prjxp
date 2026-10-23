package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.language.LanguagePackRegistry;
import de.spraener.prjxp.common.model.MethodView;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.model.SymbolReadResult;
import de.spraener.prjxp.mcp.hub.HubProjectRegistry;
import de.spraener.prjxp.mcp.hub.PipelineOrchestrator;
import de.spraener.prjxp.mcp.hub.ProjectStatus;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Phase 04 (Hub path): end-to-end test with the REAL lp-gdscript plugin JAR.
 *
 * <p>The full hub context (real {@link PipelineOrchestrator}, real Lucene index,
 * real search services) runs a GDScript fixture project through the complete
 * pipeline: plugin chunker (loaded via its own class loader from the staged
 * plugin JAR) → chunk JSONL → embedding (deterministic mock) → index swap.
 * Afterwards all three search levels must find the GDScript chunks: vector
 * search, grep (language filter via the plugin's MIME) and readBySignature
 * (symbol metadata + doc join).
 *
 * <p>The plugin JAR is staged by the {@code stageLpGdscriptPlugin} Gradle task
 * (see build.gradle) and passed in via the {@code prjxp.gdscript.e2e.pluginDir}
 * system property; only this test points {@code prjxp.language-packs.dir} at it.
 *
 * <p>The embedding model is replaced via {@code @MockitoBean} (NOT a nested
 * {@code @Configuration}: a nested configuration class in a scanned package
 * would be picked up by the host's component scan in EVERY full-context test
 * and break bean registration).
 */
@SpringBootTest(properties = {
        "prjxp.cli.enabled=false",
        "prjxp.hub.enabled=true",
        "prjxp.embeddingStoreLucene.vectorDimension=8"
})
class GdscriptPluginEndToEndTest {

    /** Replaces the configured (Ollama) model: deterministic 8-dim vectors, no model server needed. */
    @MockitoBean
    EmbeddingModel embeddingModel;

    @BeforeEach
    void stubEmbeddingModel() {
        when(embeddingModel.dimension()).thenReturn(8);
        when(embeddingModel.embedAll(any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<TextSegment> segments = inv.getArgument(0);
            return Response.from(segments.stream()
                    .map(segment -> deterministicEmbedding(segment.text()))
                    .toList());
        });
        when(embeddingModel.embed(anyString())).thenAnswer(inv ->
                Response.from(deterministicEmbedding(inv.getArgument(0))));
    }

    private static final String GDSCRIPT_SOURCE = """
            class_name Player
            extends CharacterBody2D
            ## A player that moves and shoots.

            @export var speed = 300
            var health = 100
            signal died

            ## Moves the player in the given direction.
            func move(dir):
            	position += dir * speed

            ## Fires a projectile toward the target.
            func shoot(target):
            	print("shoot at ", target)

            class AI:
            	var brain = 1
            """;

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void paths(DynamicPropertyRegistry registry) {
        registry.add("prjxp.hub.import-dir", () -> tempDir.resolve("import").toString());
        registry.add("prjxp.hub.projects-root", () -> tempDir.resolve("projects").toString());
        registry.add("prjxp.hub.live-jsonl-dir", () -> tempDir.resolve("chunks").toString());
        registry.add("prjxp.embeddingStoreLucene.indexPath", () -> tempDir.resolve("index").toString());
        registry.add("prjxp.language-packs.dir",
                () -> System.getProperty("prjxp.gdscript.e2e.pluginDir", "build/language-packs"));
    }

    @Autowired
    HubProjectRegistry registry;

    @Autowired
    PipelineOrchestrator orchestrator;

    @Autowired
    VectorSearchService vectorSearch;

    @Autowired
    GrepSearchService grepSearch;

    @Autowired
    SymbolReaderService symbolReader;

    @Autowired
    LanguagePackRegistry languagePackRegistry;

    @Test
    void gdscriptPluginPackIsLoadedAndRegistered() {
        assertThat(languagePackRegistry.mimeByLanguage())
                .as("the plugin pack must be visible in the host registry")
                .containsEntry("gdscript", "text/x-gdscript-code");
    }

    @Test
    void gdscriptProjectEndToEnd() throws Exception {
        // 1. Fixture project: GDScript source + a whitelist that admits .gd files
        Path projectDir = Files.createDirectories(tempDir.resolve("projects").resolve("gdscript-demo"));
        Files.writeString(projectDir.resolve("prjxp.yaml"), "chunoWhiteList: gd\n");
        Files.writeString(projectDir.resolve("player.gd"), GDSCRIPT_SOURCE);

        // 2. Register + run the full pipeline (chunk → embed → swap)
        registry.registerProject("gdscript-demo", projectDir);
        orchestrator.enqueue("gdscript-demo");
        awaitStatus("gdscript-demo", ProjectStatus.READY);

        // 3. Chunking: the plugin chunker produced GDScript chunks in the JSONL
        Path jsonl = projectDir.resolve("px-chunks.jsonl");
        assertThat(Files.exists(jsonl)).as("chunk JSONL").isTrue();
        String jsonlContent = Files.readString(jsonl);
        assertThat(jsonlContent).contains("text/x-gdscript-code");
        assertThat(jsonlContent).contains("gdscript_code_section");
        assertThat(jsonlContent).contains("Player");

        // 4. Vector search finds GDScript chunks (deterministic mock: the query is the
        // shoot doc's text, so that chunk must rank first)
        List<SearchHit> hits = vectorSearch.search("Fires a projectile toward the target", "gdscript-demo", null, 5);
        assertThat(hits).as("vector search hits").isNotEmpty();
        assertThat(hits.get(0).metadata()).containsKey("gdscript_code_section");
        assertThat(hits.get(0).snippet()).contains("projectile");

        // 5. Grep search with the plugin-provided language filter (gdscript → MIME)
        List<SearchHit> grepHits = grepSearch.search("speed", "gdscript-demo", "gdscript", 5);
        assertThat(grepHits).as("grep hits").isNotEmpty();

        // 6. readBySignature: symbol lookup + func/doc join for GDScript
        SymbolReadResult result = symbolReader.readBySignature("move", "Player", "gdscript-demo");
        assertThat(result.error()).as("readBySignature error").isNull();
        assertThat(result.matches()).hasSize(1);
        MethodView view = result.matches().get(0);
        assertThat(view.fqn()).isEqualTo("Player#move");
        assertThat(view.body()).contains("position += dir * speed");
        assertThat(view.javadoc()).contains("Moves the player");
    }

    private void awaitStatus(String name, ProjectStatus expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        ProjectStatus actual = null;
        while (System.currentTimeMillis() < deadline) {
            var entry = registry.entry(name);
            if (entry.isPresent()) {
                actual = entry.get().getStatus();
                if (actual == expected) {
                    return;
                }
            }
            Thread.sleep(50);
        }
        assertThat(actual).as("lifecycle status of '%s' (error: %s)", name,
                registry.entry(name).map(e -> e.getLastError()).orElse("n/a")).isEqualTo(expected);
    }

    /**
     * Deterministic 8-dim unit vector: character-frequency buckets
     * (charCode % 8). A query that is a substring of a chunk's text
     * yields a vector close to that chunk's vector (cosine ≈ 1).
     */
    private static Embedding deterministicEmbedding(String text) {
        float[] v = new float[8];
        for (int i = 0; i < text.length(); i++) {
            v[text.charAt(i) % 8] += 1f;
        }
        float norm = 0f;
        for (float f : v) {
            norm += f * f;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0f) {
            for (int i = 0; i < 8; i++) {
                v[i] /= norm;
            }
        }
        return Embedding.from(v);
    }
}
