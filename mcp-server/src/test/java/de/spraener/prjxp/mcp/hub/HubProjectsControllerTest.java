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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
    private EmbeddedArchiveImporter embeddedImporter;   // mocked — validation/processing stubbed per test
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        Path projectsRoot = Files.createDirectories(tempRoot.resolve("projects"));
        Path indexDir = Files.createDirectories(tempRoot.resolve("index"));
        Path importDir = Files.createDirectories(tempRoot.resolve("import"));

        hub = new HubProperties();
        hub.setProjectsRoot(projectsRoot.toString());
        hub.setImportDir(importDir.toString());
        luceneStore = new LuceneEmbeddingStore(indexDir, 8);
        registry = new HubProjectRegistry(new PrjXPConfig(), hub, new PxChunkDaoProvider(List.of()),
                luceneStore, mock(EmbeddingModel.class), new ProjectConfigFileParser());
        lifecycle = new ProjectLifecycleService(registry, luceneStore, hub);
        orchestrator = mock(PipelineOrchestrator.class);
        compatibilityChecker = mock(EmbeddingCompatibilityChecker.class);
        embeddingModel = mock(EmbeddingModel.class);
        objMapper = mock(ObjectMapper.class);

        embeddedImporter = mock(EmbeddedArchiveImporter.class);
        controller = new HubProjectsController(
                registry, lifecycle, orchestrator, registry, compatibilityChecker, embeddingModel, luceneStore, hub, objMapper, new ProjectConfigFileParser(),
                embeddedImporter);
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

        mockMvc.perform(get("/api/projects"))
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

        mockMvc.perform(delete("/api/projects/alpha"))
                .andExpect(status().isNoContent());

        assertThat(registry.entry("alpha")).isEmpty();
        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void deleteOfUnknownProjectSurfacesAs500() throws Exception {
        // Documented choice (task doc §4.3): the controller adds no exception mapping, so
        // UnknownProjectException propagates unhandled — in the full application Spring Boot's
        // default error handling surfaces it as HTTP 500. In standalone MockMvc (no global
        // exception handler) the unhandled exception is rethrown wrapped in a ServletException.
        assertThatThrownBy(() -> mockMvc.perform(delete("/api/projects/ghost")))
                .isInstanceOf(ServletException.class)
                .hasRootCauseInstanceOf(UnknownProjectException.class)
                .hasMessageContaining("ghost");
    }

    // ------------------------------------------------------------------ Phase 06: reindex

    @Test
    void reindexOfKnownProjectEnqueuesPipelineAndReturns202() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        mockMvc.perform(post("/api/projects/alpha/reindex"))
                .andExpect(status().isAccepted());

        verify(orchestrator).enqueue("alpha");
    }

    @Test
    void reindexOfWorksForFailedProject() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        registry.setStatus("alpha", ProjectStatus.FAILED, "embed blew up");

        mockMvc.perform(post("/api/projects/alpha/reindex"))
                .andExpect(status().isAccepted());

        verify(orchestrator).enqueue("alpha");   // FAILED is re-runnable via explicit reindex
    }

    @Test
    void reindexOfUnknownProjectSurfacesAs500() throws Exception {
        // same error behavior as DELETE: UnknownProjectException propagates unhandled
        assertThatThrownBy(() -> mockMvc.perform(post("/api/projects/ghost/reindex")))
                .isInstanceOf(ServletException.class)
                .hasRootCauseInstanceOf(UnknownProjectException.class)
                .hasMessageContaining("ghost");

        verify(orchestrator, never()).enqueue(anyString());
    }

    // ------------------------------------------------------------------ DocPipe

    @Test
    void docpipeOfKnownProjectEnqueuesDocChainAndReturns202() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        mockMvc.perform(post("/api/projects/alpha/docpipe"))
                .andExpect(status().isAccepted());

        verify(orchestrator).enqueueDocPipe("alpha");
    }

    @Test
    void docpipeOfUnknownProjectSurfacesAs500() throws Exception {
        // same error behavior as reindex: UnknownProjectException propagates unhandled
        assertThatThrownBy(() -> mockMvc.perform(post("/api/projects/ghost/docpipe")))
                .isInstanceOf(ServletException.class)
                .hasRootCauseInstanceOf(UnknownProjectException.class)
                .hasMessageContaining("ghost");

        verify(orchestrator, never()).enqueueDocPipe(anyString());
    }

    // ------------------------------------------------------------------ Reindex status endpoint

    @Test
    void getReindexStatusReturnsIdleForProjectWithNoActiveReindex() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        mockMvc.perform(get("/api/projects/alpha/reindex-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDLE"))
                .andExpect(jsonPath("$.progress").value(0));
    }

    @Test
    void getReindexStatusReturnsChunkingWhenSet() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        registry.setReindexStatus("alpha", ReindexStatus.CHUNKING);
        registry.setReindexProgress("alpha", 25);

        mockMvc.perform(get("/api/projects/alpha/reindex-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CHUNKING"))
                .andExpect(jsonPath("$.progress").value(25));
    }

    @Test
    void getReindexStatusReturnsEmbeddingWhenSet() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        registry.setReindexStatus("alpha", ReindexStatus.EMBEDDING);
        registry.setReindexProgress("alpha", 60);

        mockMvc.perform(get("/api/projects/alpha/reindex-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EMBEDDING"))
                .andExpect(jsonPath("$.progress").value(60));
    }

    @Test
    void getReindexStatusReturnsCompleteWhenSet() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        registry.setReindexStatus("alpha", ReindexStatus.COMPLETE);
        registry.setReindexProgress("alpha", 100);

        mockMvc.perform(get("/api/projects/alpha/reindex-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE"))
                .andExpect(jsonPath("$.progress").value(100));
    }

    @Test
    void getReindexStatusReturnsDefaultsForUnknownProject() throws Exception {
        mockMvc.perform(get("/api/projects/ghost/reindex-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDLE"))
                .andExpect(jsonPath("$.progress").value(0));
    }

    @Test
    void resetReindexStatusClearsProgress() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));
        registry.setReindexStatus("alpha", ReindexStatus.EMBEDDING);
        registry.setReindexProgress("alpha", 50);

        registry.resetReindexStatus("alpha");

        mockMvc.perform(get("/api/projects/alpha/reindex-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IDLE"))
                .andExpect(jsonPath("$.progress").value(0));
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
                "1", "10", content.length(), 0, java.util.Map.of(), content, vector, null, null);
    }

    // ------------------------------------------------------------------ Phase 06: createProject

    @Test
    void createProjectCreatesNewProjectAndReturnsImporting() throws Exception {
        var request = new de.spraener.prjxp.mcp.ProjectCreateRequest("new-project", "SNAPSHOT", null, null, null, null);

        var result = controller.createProject(request);

        assertThat(result.name()).isEqualTo("new-project");
        assertThat(result.status()).isEqualTo("IMPORTING");
        assertThat(registry.entry("new-project")).isPresent();
        verify(orchestrator).enqueue("new-project");
    }

    @Test
    void createProjectWithLiveKind() throws Exception {
        hub.setImportDir(tempRoot.resolve("import").toString());
        Files.createDirectories(tempRoot.resolve("import"));

        var request = new de.spraener.prjxp.mcp.ProjectCreateRequest("live-project", "LIVE", null, null, null, null);
        var result = controller.createProject(request);

        assertThat(result.kind()).isEqualTo("LIVE");
        assertThat(registry.entry("live-project")).isPresent();
    }

    // ------------------------------------------------------------------ Phase 06: updateProject

    @Test
    void updateProjectUpdatesConfig() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        var updates = new de.spraener.prjxp.mcp.ProjectUpdateRequest(null, "go,rs", 64, null, null);
        controller.updateProject("alpha", updates);

        var def = registry.definitionOf("alpha");
        assertThat(def).isNotNull();
        assertThat(def.getChunoWhiteList()).isEqualTo("go,rs");
        assertThat(def.getTibedBatchSize()).isEqualTo(64);
    }

    @Test
    void updateProjectOfUnknownThrowsNotFound() {
        var updates = new de.spraener.prjxp.mcp.ProjectUpdateRequest(null, "go", null, null, null);

        assertThatThrownBy(() -> controller.updateProject("ghost", updates))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    // ------------------------------------------------------------------ Phase 06: uploadArchive

    @Test
    void uploadArchiveSavesFileToImportDir() throws Exception {
        Files.createDirectories(tempRoot.resolve("import"));

        MockMultipartFile file = new MockMultipartFile(
                "file", "my-project.tar.gz", "application/x-gzip", "fake-tar-content".getBytes());

        controller.uploadArchive(file);

        assertThat(Files.exists(tempRoot.resolve("import/my-project.tar.gz"))).isTrue();
    }

    // ------------------------------------------------------------------ Phase 06: scanLiveProjects (live-scan)

    @Test
    void scanLiveProjectsWithNoImportDirReturnsEmpty() {
        hub.setImportDir(tempRoot.resolve("nonexistent-import").toString());

        var candidates = controller.scanLiveProjects();
        assertThat(candidates).isEmpty();
    }

    @Test
    void scanLiveProjectsWithImportDirReturnsCandidates() throws Exception {
        Path importDir = tempRoot.resolve("import");
        Files.createDirectories(importDir);

        Path projDir = Files.createDirectories(importDir.resolve("my-live-project"));
        Files.writeString(projDir.resolve("Main.java"), "class Main {}");

        var candidates = controller.scanLiveProjects();
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).name()).isEqualTo("my-live-project");
        assertThat(candidates.get(0).hasConfigFile()).isFalse();
    }

    // ------------------------------------------------------------------ Phase 06: getProjectStats

    @Test
    void getProjectStatsReturnsStatsForKnownProject() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        var stats = controller.getProjectStats("alpha");
        assertThat(stats.name()).isEqualTo("alpha");
    }

    @Test
    void getProjectStatsOfUnknownThrowsNotFound() {
        assertThatThrownBy(() -> controller.getProjectStats("ghost"))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    // ------------------------------------------------------------------ Phase 06: projectInfos includes stats fields

    @Test
    void listReturnsProjectInfosWithStatsFields() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("alpha"))
                .andExpect(jsonPath("$[0].kind").value("SNAPSHOT"))
                .andExpect(jsonPath("$[0].chunkCount").isNotEmpty())
                .andExpect(jsonPath("$[0].embeddingCount").isNotEmpty())
                .andExpect(jsonPath("$[0].fileCount").isNotEmpty());
    }

    // ------------------------------------------------------------------ upload-embedded: bad filename -> 400

    @Test
    void uploadEmbeddedWithBadFilenameReturns400() throws Exception {
        doThrow(new IllegalArgumentException("File must be named <ProjectName>-embedded.(jsonl|gz|tgz|zip)"))
                .when(embeddedImporter).validate(any(Path.class));

        MockMultipartFile file = new MockMultipartFile(
                "file", "bad-name.jsonl", "application/json", "{}\n".getBytes());

        mockMvc.perform(multipart("/api/projects/upload-embedded")
                .file(file))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ prompt CRUD

    @Test
    void updateProjectPersistsPrompt() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        var updates = new de.spraener.prjxp.mcp.ProjectUpdateRequest(null, null, null, "Focus on payments.", null);
        var result = controller.updateProject("alpha", updates);

        assertThat(registry.definitionOf("alpha").getPrompt()).isEqualTo("Focus on payments.");
        assertThat(result.prompt()).isEqualTo("Focus on payments.");
    }

    @Test
    void updateProjectBlankPromptClearsIt() throws Exception {
        Path dir = projectDir("alpha");
        Files.writeString(dir.resolve("prjxp.yaml"), "name: alpha\nprompt: \"Focus on payments.\"\n");
        registry.registerProject("alpha", dir);

        var updates = new de.spraener.prjxp.mcp.ProjectUpdateRequest(null, null, null, "   ", null);
        controller.updateProject("alpha", updates);

        assertThat(registry.definitionOf("alpha").getPrompt()).isNull();
    }

    @Test
    void listExposesPrompt() throws Exception {
        Path dir = projectDir("alpha");
        Files.writeString(dir.resolve("prjxp.yaml"), "name: alpha\nprompt: \"Focus on payments.\"\n");
        registry.registerProject("alpha", dir);

        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].prompt").value("Focus on payments."));
    }

    // ------------------------------------------------------------------ fulltext switch

    @Test
    void updateProjectPersistsFulltextAndDoesNotEnqueueReindex() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        var updates = new de.spraener.prjxp.mcp.ProjectUpdateRequest(null, null, null, null, true);
        var result = controller.updateProject("alpha", updates);

        assertThat(registry.definitionOf("alpha").isFulltext()).isTrue();
        assertThat(result.fulltext()).isTrue();
        assertThat(Files.readString(projectDir("alpha").resolve("prjxp.yaml"))).contains("fulltext: true");
        verify(orchestrator, never()).enqueue(anyString());   // fulltext change never triggers auto-reindex
    }

    @Test
    void updateProjectWithoutFulltextLeavesSwitchUntouched() throws Exception {
        registry.registerProject("alpha", projectDir("alpha"));

        var updates = new de.spraener.prjxp.mcp.ProjectUpdateRequest(null, "go", null, null, null);
        var result = controller.updateProject("alpha", updates);

        assertThat(registry.definitionOf("alpha").isFulltext()).isFalse();
        assertThat(result.fulltext()).isFalse();
        verify(orchestrator, never()).enqueue(anyString());
    }

    @Test
    void listExposesFulltextFromYaml() throws Exception {
        Path dir = projectDir("alpha");
        Files.writeString(dir.resolve("prjxp.yaml"), "name: alpha\nfulltext: true\n");
        registry.registerProject("alpha", dir);

        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fulltext").value(true));
    }
}
