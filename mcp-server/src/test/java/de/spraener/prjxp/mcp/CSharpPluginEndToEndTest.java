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
 * WP-2 Phase 04 (Hub path): end-to-end test with the REAL lp-csharp plugin JAR.
 *
 * <p>The full hub context (real {@link PipelineOrchestrator}, real Lucene index,
 * real search services) runs a C# fixture project through the complete pipeline:
 * plugin chunker (loaded via its own class loader from the staged plugin JAR) →
 * chunk JSONL → embedding (deterministic mock) → index swap. Afterwards all three
 * search levels must find the C# chunks: vector search, grep (language filter via
 * the plugin's MIME) and readBySignature (symbol metadata + doc join).
 *
 * <p>The plugin JAR is staged by the {@code stageLpCsharpPlugin} Gradle task
 * (see build.gradle) and passed in via the {@code prjxp.csharp.e2e.pluginDir}
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
class CSharpPluginEndToEndTest {

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

    private static final String CSHARP_SOURCE = """
            using System;

            namespace Acme
            {
                /// <summary>
                /// A calculator that tracks how many operations were performed.
                /// </summary>
                public class Calculator
                {
                    private int operationsCount;

                    public Calculator()
                    {
                        operationsCount = 0;
                    }

                    /// <summary>
                    /// Adds two integers and returns the sum.
                    /// </summary>
                    public int Add(int a, int b)
                    {
                        operationsCount++;
                        return a + b;
                    }

                    /// <summary>
                    /// Multiplies two integers and returns the product.
                    /// </summary>
                    public int Multiply(int a, int b)
                    {
                        operationsCount++;
                        return a * b;
                    }
                }
            }
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
                () -> System.getProperty("prjxp.csharp.e2e.pluginDir", "build/language-packs"));
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
    void csharpPluginPackIsLoadedAndRegistered() {
        assertThat(languagePackRegistry.mimeByLanguage())
                .as("the plugin pack must be visible in the host registry")
                .containsEntry("csharp", "text/x-csharp-code");
    }

    @Test
    void csharpProjectEndToEnd() throws Exception {
        // 1. Fixture project: C# source + a whitelist that admits .cs files
        Path projectDir = Files.createDirectories(tempDir.resolve("projects").resolve("csharp-demo"));
        Files.writeString(projectDir.resolve("prjxp.yaml"), "chunoWhiteList: cs\n");
        Files.writeString(projectDir.resolve("Calculator.cs"), CSHARP_SOURCE);

        // 2. Register + run the full pipeline (chunk → embed → swap)
        registry.registerProject("csharp-demo", projectDir);
        orchestrator.enqueue("csharp-demo");
        awaitStatus("csharp-demo", ProjectStatus.READY);

        // 3. Chunking: the plugin chunker produced C# chunks in the JSONL
        Path jsonl = projectDir.resolve("px-chunks.jsonl");
        assertThat(Files.exists(jsonl)).as("chunk JSONL").isTrue();
        String jsonlContent = Files.readString(jsonl);
        assertThat(jsonlContent).contains("text/x-csharp-code");
        assertThat(jsonlContent).contains("csharp_code_section");
        assertThat(jsonlContent).contains("Acme.Calculator");

        // 4. Vector search finds C# chunks (deterministic mock: the query is a
        // substring of the Multiply doc, so that chunk must rank first)
        List<SearchHit> hits = vectorSearch.search("returns the product", "csharp-demo", null, 5);
        assertThat(hits).as("vector search hits").isNotEmpty();
        assertThat(hits.get(0).metadata()).containsKey("csharp_code_section");
        assertThat(hits.get(0).snippet()).contains("product");

        // 5. Grep search with the plugin-provided language filter (csharp → MIME)
        List<SearchHit> grepHits = grepSearch.search("operationsCount", "csharp-demo", "csharp", 5);
        assertThat(grepHits).as("grep hits").isNotEmpty();

        // 6. readBySignature: symbol lookup + method/doc join for C#
        SymbolReadResult result = symbolReader.readBySignature("Add", "Acme.Calculator", "csharp-demo");
        assertThat(result.error()).as("readBySignature error").isNull();
        assertThat(result.matches()).hasSize(1);
        MethodView view = result.matches().get(0);
        assertThat(view.fqn()).isEqualTo("Acme.Calculator#Add");
        assertThat(view.body()).contains("return a + b");
        assertThat(view.javadoc()).contains("Adds two integers");
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
