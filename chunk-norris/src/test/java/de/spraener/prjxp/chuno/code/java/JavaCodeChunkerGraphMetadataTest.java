package de.spraener.prjxp.chuno.code.java;

import de.spraener.prjxp.chuno.spring.SpringPreWalkEvent;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.graph.GraphMetadata;
import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end (Spring) test: publishing the pre-walk event drives the real
 * {@link JavaCodeGraphBuilder} + {@code LeidenWeighter}, and the {@link JavaCodeChunker}
 * stamps the resulting graph-node id and Leiden rating onto the chunks.
 */
@SpringBootTest
@ActiveProfiles("test")
class JavaCodeChunkerGraphMetadataTest {

    @TempDir
    Path root;

    @Autowired
    JavaCodeChunker uut;

    @Autowired
    JavaCodeGraphBuilder graphBuilder;

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Test
    void chunks_getGraphNodeAndLeidenMetadata() throws Exception {
        Files.createDirectories(root.resolve("demo"));
        Files.writeString(root.resolve("demo/Sample.java"), """
                package demo;
                public class Sample {
                    public int add(int a, int b) { return a + b; }
                }
                """);

        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("p");
        pd.setRootDir(root.toString());
        pd.setJsonlFile(root.resolve("out.jsonl").toString());

        eventPublisher.publishEvent(new SpringPreWalkEvent<>(new PrjXPConfig(), pd));

        List<PxChunk> chunks = uut.chunk(root.resolve("demo/Sample.java").toFile()).toList();

        // class-frame chunk
        PxChunk frame = chunks.stream()
                .filter(c -> "classFrame".equals(c.getMetadata().get("java_code_section")))
                .filter(c -> "demo.Sample".equals(c.getId()))
                .findFirst()
                .orElseThrow();
        assertThat(frame.getMetadata().get(GraphMetadata.GRAPH_NODE)).isEqualTo("demo.Sample");
        assertThat(frame.getMetadata().get(GraphMetadata.LEIDEN_CLUSTER_ID)).isNotNull();
        assertThat(frame.getMetadata().get(GraphMetadata.LEIDEN_WEIGHT)).isNotNull();

        // method chunk for add
        PxChunk method = chunks.stream()
                .filter(c -> "method".equals(c.getMetadata().get("java_code_section")))
                .findFirst()
                .orElseThrow();
        assertThat(method.getMetadata().get(GraphMetadata.GRAPH_NODE)).isEqualTo("demo.Sample#add(int,int)");
        assertThat(method.getMetadata().get(GraphMetadata.LEIDEN_CLUSTER_ID)).isNotNull();
        assertThat(method.getMetadata().get(GraphMetadata.LEIDEN_WEIGHT)).isNotNull();

        // Consistency: EVERY chunk carrying a graph_node also carries both Leiden keys.
        assertThat(chunks)
                .filteredOn(c -> c.getMetadata().get(GraphMetadata.GRAPH_NODE) != null)
                .isNotEmpty()
                .allSatisfy(c -> {
                    assertThat(c.getMetadata().get(GraphMetadata.LEIDEN_CLUSTER_ID)).isNotNull();
                    assertThat(c.getMetadata().get(GraphMetadata.LEIDEN_WEIGHT)).isNotNull();
                });
    }

    /**
     * The hub's live-update path ({@code ChunkProcess.executeForFiles}) never publishes the
     * pre-walk event, so no graph is built for that run. Chunks must then carry no graph metadata
     * at all — a bare {@code graph_node} without {@code leiden_*} would be unusable for ranking
     * and would break the metadata contract asserted above.
     */
    @Test
    void livePathWithoutGraphBuildStampsNoGraphMetadata() throws Exception {
        graphBuilder.beginRun();

        Files.writeString(root.resolve("Live.java"), """
                package demo;
                public class Live {
                    public String hello() { return "hi"; }
                }
                """);

        List<PxChunk> chunks = uut.chunk(root.resolve("Live.java").toFile()).toList();

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).anySatisfy(c ->
                assertThat(c.getMetadata().get("java_code_section")).isEqualTo("classFrame"));
        assertThat(chunks).allSatisfy(c -> assertThat(c.getMetadata())
                .doesNotContainKeys(GraphMetadata.GRAPH_NODE,
                        GraphMetadata.LEIDEN_CLUSTER_ID,
                        GraphMetadata.LEIDEN_WEIGHT));
    }
}
