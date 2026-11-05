package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.PrjXPConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-context smoke test for CLI mode ({@code prjxp.hub.enabled=false}): the web/MCP/REST
 * stack and the hub registry must be absent, while the shared infrastructure (config, Lucene
 * store, pipeline) stays available. The {@code test} profile is active so the bundled CLI
 * runners ({@code @Profile("!test")}) do not fire during the test.
 * <p>
 * {@code spring.main.web-application-type=none} is set explicitly: the inlined test property
 * is added after {@code EnvironmentPostProcessor}s run, so the {@link CliModeEnvironmentPostProcessor}
 * cannot be relied upon to suppress the web server here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "prjxp.hub.enabled=false",
        "spring.main.web-application-type=none"
})
@ActiveProfiles("test")
class CliModeContextTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void luceneIndex(DynamicPropertyRegistry registry) {
        registry.add("prjxp.embeddingStoreLucene.indexPath", () -> tempDir.resolve("index").toString());
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void cliModeContextStarts() {
        assertThat(context).isNotNull();
    }

    @Test
    void noProjectRegistryInCliMode() {
        assertThat(context.getBeanProvider(ProjectRegistry.class).getIfAvailable())
                .as("No ProjectRegistry implementation may exist in CLI mode (Static deleted, Hub is hub-only)")
                .isNull();
    }

    @Test
    void noMcpRestControllerInCliMode() {
        assertThat(context.getBeanProvider(McpRestController.class).getIfAvailable())
                .as("The MCP REST controller is part of the hub web stack")
                .isNull();
    }

    @Test
    void noWebServerInCliMode() {
        assertThat(context.getBeanProvider(WebServer.class).getIfAvailable())
                .as("CLI mode must not start an embedded web server")
                .isNull();
    }

    @Test
    void sharedInfrastructureRemainsAvailable() {
        assertThat(context.getBean(PrjXPConfig.class)).isNotNull();
    }
}
