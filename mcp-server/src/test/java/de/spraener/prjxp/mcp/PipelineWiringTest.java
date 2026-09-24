package de.spraener.prjxp.mcp;

import de.spraener.prjxp.chuno.ChunkProcess;
import de.spraener.prjxp.tibed.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 01 (DockerHub): the hub app must be able to run chunk-norris and tibed
 * in-process. With {@code prjxp.cli.enabled=false} the pipeline beans are present,
 * but no CLI runner from those modules fires.
 */
@SpringBootTest(properties = {
        "prjxp.cli.enabled=false",
        "prjxp.embedding-store-type=lucene"
})
class PipelineWiringTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void luceneIndex(DynamicPropertyRegistry registry) {
        registry.add("prjxp.embeddingStoreLucene.indexPath", () -> tempDir.resolve("lucene-index").toString());
    }

    @Autowired
    ApplicationContext context;

    @Test
    void pipelineBeansAreAvailableInProcess() {
        assertThat(context.getBean(ChunkProcess.class)).isNotNull();
        assertThat(context.getBean(EmbeddingService.class)).isNotNull();
    }

    @Test
    void noCliRunnerFromPipelineModulesIsPresent() {
        assertThat(context.getBeansOfType(CommandLineRunner.class).values())
                .filteredOn(runner -> runner.getClass().getName().startsWith("de.spraener.prjxp.chuno.")
                        || runner.getClass().getName().startsWith("de.spraener.prjxp.tibed."))
                .as("CLI runners of chunk-norris/tibed must be disabled via prjxp.cli.enabled=false")
                .isEmpty();
    }
}
