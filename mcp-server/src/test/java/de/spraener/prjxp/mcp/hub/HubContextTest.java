package de.spraener.prjxp.mcp.hub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Phase 05 (HubDeployment): full-context smoke test for the deployable hub mode —
 * the same bean wiring that {@code entry.sh}'s {@code hub} branch and the compose
 * {@code hub} service rely on (gated by properties, no Spring profile).
 */
@SpringBootTest(properties = {
        "prjxp.hub.enabled=true",
        "prjxp.cli.enabled=false",
        "prjxp.embedding-store-type=lucene"
})
class HubContextTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void hubPaths(DynamicPropertyRegistry registry) {
        registry.add("prjxp.hub.import-dir", () -> tempDir.resolve("import").toString());
        registry.add("prjxp.hub.projects-root", () -> tempDir.resolve("projects").toString());
        registry.add("prjxp.embeddingStoreLucene.indexPath", () -> tempDir.resolve("index").toString());
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private PipelineOrchestrator orchestrator;

    @Test
    void hubContextStartsAndExposesHubBeans() {
        assertThat(context.getBean(ImportPoller.class)).isNotNull();
        assertThat(context.getBean(HubProjectRegistry.class)).isNotNull();
        assertThat(context.getBean(PipelineOrchestrator.class)).isNotNull();
        assertThat(context.getBean(HubProjectsController.class)).isNotNull();
    }

    @Test
    void selfHealOnEmptyProjectsRootDoesNotThrow() {
        assertThatCode(orchestrator::selfHeal).doesNotThrowAnyException();
    }
}
