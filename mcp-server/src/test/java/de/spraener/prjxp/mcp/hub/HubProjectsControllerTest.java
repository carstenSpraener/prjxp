package de.spraener.prjxp.mcp.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.model.EmbeddedChunkRecord;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.mcp.UnknownProjectException;
import de.spraener.prjxp.tibed.EmbeddingCompatibilityChecker;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import org.springframework.mock.web.MockMultipartFile;

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
    private PipelineOrchestrator orchestrator;   // mocked — enqueues are captured, no real pipeline
    private EmbeddingCompatibilityChecker compatibilityChecker;  // mocked
    private EmbeddingModel embeddingModel;   // mocked
    private ObjectMapper objMapper;          // mocked
    private HubProjectsController controller;  // for direct method calls (multipart tests)
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
        orchestrator = mock(PipelineOrchestrator.class);
        compatibilityChecker = mock(EmbeddingCompatibilityChecker.class);
        embeddingModel = mock(EmbeddingModel.class);
        objMapper = mock(ObjectMapper.class);

        controller = new HubProjectsController(
                registry, lifecycle, orchestrator, registry, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper);
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

    // ------------------------------------------------------------------ Phase 06: reindex

    @Test
    void reindexOfKnownProjectEnqueuesPipelineAndReturns202() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        mockMvc.perform(post("/prjxp/projects/alpha/reindex"))
                .andExpect(status().isAccepted());

        verify(orchestrator).enqueue("alpha");
    }

    @Test
    void reindexOfWorksForFailedProject() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        registry.setStatus("alpha", ProjectStatus.FAILED, "embed blew up");

        mockMvc.perform(post("/prjxp/projects/alpha/reindex"))
                .andExpect(status().isAccepted());

        verify(orchestrator).enqueue("alpha");   // FAILED is re-runnable via explicit reindex
    }

    @Test
    void reindexOfUnknownProjectSurfacesAs500() throws Exception {
        // same error behavior as DELETE: UnknownProjectException propagates unhandled
        assertThatThrownBy(() -> mockMvc.perform(post("/prjxp/projects/ghost/reindex")))
                .isInstanceOf(ServletException.class)
                .hasRootCauseInstanceOf(UnknownProjectException.class)
                .hasMessageContaining("ghost");

        verify(orchestrator, never()).enqueue(anyString());
    }

    // ------------------------------------------------------------------ Phase 07: importEmbeddings

    @Test
    void importEmbeddingsOfUnknownProjectThrowsException() {
        // Direct controller call — throws UnknownProjectException for unknown projects
        try {
            controller.importEmbeddings("ghost", new MockMultipartFile("file", "test.jsonl", "application/json", "{}".getBytes()));
            org.junit.jupiter.api.Assertions.fail("Expected UnknownProjectException");
        } catch (UnknownProjectException e) {
            assertThat(e.getMessage()).contains("ghost");
        }
    }

    @Test
    void importEmbeddingsWithIncompatibleVectorsThrowsException() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        // Mock compatibility checker to return INCOMPATIBLE
        when(compatibilityChecker.check(any(List.class), any(EmbeddingModel.class), anyInt(), anyFloat(), anyFloat()))
                .thenReturn(new EmbeddingCompatibilityChecker.CheckReport(0.5f, 4, EmbeddingCompatibilityChecker.Verdict.INCOMPATIBLE));

        // Mock ObjectMapper to return a valid record
        when(objMapper.readValue(anyString(), any(Class.class)))
                .thenReturn(createEmbeddedChunkRecord("c1", "test content", new float[8]));

        // Direct controller call — returns ResponseEntity with bad request status
        var response = controller.importEmbeddings("alpha", new MockMultipartFile("file", "test.jsonl", "application/json",
                "{\"id\":\"c1\",\"content\":\"test\"}".getBytes()));

        assertThat(response.getStatusCode().value()).isEqualTo(400);   // BAD_REQUEST
        assertThat(response.getBody()).contains("incompatible");
    }

    @Test
    void importEmbeddingsWithCompatibleVectorsReturnsOk() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        // Mock compatibility checker to return COMPATIBLE
        when(compatibilityChecker.check(any(List.class), any(EmbeddingModel.class), anyInt(), anyFloat(), anyFloat()))
                .thenReturn(new EmbeddingCompatibilityChecker.CheckReport(0.99f, 4, EmbeddingCompatibilityChecker.Verdict.COMPATIBLE));

        // Mock ObjectMapper to return a valid record with a vector
        EmbeddedChunkRecord mockRecord = createEmbeddedChunkRecord("c1", "test content", new float[8]);
        when(objMapper.readValue(anyString(), any(Class.class)))
                .thenReturn(mockRecord);

        // Direct controller call
        var response = controller.importEmbeddings("alpha", new MockMultipartFile("file", "test.jsonl", "application/json",
                "{\"id\":\"c1\",\"content\":\"test\"}".getBytes()));

        assertThat(response.getStatusCode().value()).isEqualTo(200);   // OK
        assertThat(response.getBody()).contains("Imported");
    }

    private EmbeddedChunkRecord createEmbeddedChunkRecord(String id, String content, float[] vector) {
        return new EmbeddedChunkRecord(id, "text/plain", "/test.java", null, 0, 1,
                "1", "10", content.length(), 0, java.util.Map.of(), content, vector);
    }
}
