package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.mcp.UnknownProjectException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * REST contract of the hub project endpoints against a REAL registry + lifecycle (temp dirs, real index).
 */
class HubProjectsControllerTest {

    @TempDir
    Path tempRoot;

    private HubProperties hub;
    private LuceneEmbeddingStore luceneStore;
    private HubProjectRegistry registry;
    private ProjectLifecycleService lifecycle;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        Path projectsRoot = Files.createDirectories(tempRoot.resolve("projects"));
        Path indexDir = Files.createDirectories(tempRoot.resolve("index"));

        hub = new HubProperties();
        hub.setProjectsRoot(projectsRoot.toString());
        luceneStore = new LuceneEmbeddingStore(indexDir, 8);
        registry = new HubProjectRegistry(new PrjXPConfig(), hub, new PxChunkDaoProvider(List.of()),
                luceneStore, mock(EmbeddingModel.class), new ProjectConfigFileParser());
        lifecycle = new ProjectLifecycleService(registry, luceneStore, hub);

        HubProjectsController controller = new HubProjectsController(registry, lifecycle);
        mockMvc = standaloneSetup(controller).build();
    }

    @AfterEach
    void tearDown() {
        luceneStore.close();   // release the Lucene write lock so the temp dir can be cleaned up
    }

    private Path projectDir(String name) throws Exception {
        return Files.createDirectories(tempRoot.resolve("projects").resolve(name));
    }

    @Test
    void listReturnsProjectInfosWithStatus() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));   // IMPORTING, no error yet
        registry.registerProject("beta", projectDir("beta"));
        registry.setStatus("beta", ProjectStatus.FAILED, "embed blew up");

        mockMvc.perform(get("/prjxp/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("alpha"))
                .andExpect(jsonPath("$[0].status").value("IMPORTING"))
                .andExpect(jsonPath("$[0].lastError").isEmpty())
                .andExpect(jsonPath("$[1].name").value("beta"))
                .andExpect(jsonPath("$[1].status").value("FAILED"))
                .andExpect(jsonPath("$[1].lastError").value("embed blew up"));
    }

    @Test
    void deleteRemovesProjectAndReturns204() throws Exception {
        Path dir = projectDir("alpha");
        Files.writeString(dir.resolve("prjxp.yaml"), "name: alpha\n");
        registry.registerProject("alpha", dir);

        mockMvc.perform(delete("/prjxp/projects/alpha"))
                .andExpect(status().isNoContent());

        assertThat(registry.entry("alpha")).isEmpty();
        mockMvc.perform(get("/prjxp/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void deleteOfUnknownProjectSurfacesAs500() throws Exception {
        // Documented choice (task doc §4.3): the controller adds no exception mapping, so
        // UnknownProjectException propagates unhandled — in the full application Spring Boot's
        // default error handling surfaces it as HTTP 500. In standalone MockMvc (no global
        // exception handler) the unhandled exception is rethrown wrapped in a ServletException.
        assertThatThrownBy(() -> mockMvc.perform(delete("/prjxp/projects/ghost")))
                .isInstanceOf(ServletException.class)
                .hasRootCauseInstanceOf(UnknownProjectException.class)
                .hasMessageContaining("ghost");
    }
}
