package de.spraener.prjxp.mcp.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.docpipe.DocPipeRunException;
import de.spraener.prjxp.docpipe.DocPipeRunner;
import de.spraener.prjxp.docpipe.config.JobCreationService;
import de.spraener.prjxp.docpipe.model.DPJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link DocPipePipelineListener}: the hub-side glue that runs DocPipe after a finished root
 * run (opt-in via .dp/ configuration) and schedules the document-embedding retrigger with the
 * manifest as {@link DocumentSource}. DocPipe failures are non-fatal.
 */
class DocPipePipelineListenerTest {

    @TempDir
    Path projectRoot;

    private ProjectDefinition def;
    private HubProjectRegistry registry;
    private JobCreationService jobCreationService;
    private DocPipeRunner docPipeRunner;
    private PipelineOrchestrator orchestrator;

    private DocPipePipelineListener uut;

    @BeforeEach
    void setUp() throws Exception {
        def = new ProjectDefinition();
        def.setName("p1");
        def.setRootDir(projectRoot.toString());

        registry = mock(HubProjectRegistry.class);
        when(registry.definitionOf("p1")).thenReturn(def);
        jobCreationService = mock(JobCreationService.class);
        docPipeRunner = mock(DocPipeRunner.class);
        orchestrator = mock(PipelineOrchestrator.class);

        uut = new DocPipePipelineListener(jobCreationService, docPipeRunner, registry, new ObjectMapper());
    }

    private Path manifestPath() throws Exception {
        Path dir = projectRoot.resolve(".prjxp-data").resolve("docpipe");
        Files.createDirectories(dir);
        return dir.resolve("manifest.json");
    }

    @Test
    void noDotDPDirs_noOp() throws Exception {
        when(jobCreationService.readJobs(any())).thenReturn(Stream.empty());
        PipelineRun run = PipelineRun.root("p1");

        uut.onPipelineStart(orchestrator, "p1", run);
        uut.onPipelineFinished(orchestrator, "p1", run);

        verify(docPipeRunner, never()).runForProject(any());
        verify(orchestrator, never()).scheduleEmbedding(anyString(), any());
    }

    @Test
    void success_schedulesEmbeddingWithManifestSource() throws Exception {
        DPJob job = new DPJob();
        when(jobCreationService.readJobs(any())).thenReturn(Stream.of(job));
        doAnswer(inv -> {
            Files.writeString(manifestPath(), "[\"docs/a.md\"]");
            return null;
        }).when(docPipeRunner).runForProject(def);

        uut.onPipelineFinished(orchestrator, "p1", PipelineRun.root("p1"));

        org.mockito.ArgumentCaptor<DocumentSource> captor = org.mockito.ArgumentCaptor.forClass(DocumentSource.class);
        verify(orchestrator).scheduleEmbedding(eq("p1"), captor.capture());
        List<String> docs = captor.getValue().documentStream().map(Path::toString).toList();
        assertThat(docs).containsExactly("docs/a.md");
    }

    @Test
    void partialFailure_stillSchedulesWhenManifestExists() throws Exception {
        DPJob job = new DPJob();
        when(jobCreationService.readJobs(any())).thenReturn(Stream.of(job));
        doThrow(new DocPipeRunException("boom")).when(docPipeRunner).runForProject(def);
        Files.writeString(manifestPath(), "[\"docs/a.md\"]");   // written before the failing task

        uut.onPipelineFinished(orchestrator, "p1", PipelineRun.root("p1"));

        verify(orchestrator).scheduleEmbedding(eq("p1"), any(DocumentSource.class));
    }

    @Test
    void failureWithoutManifest_noSchedule() throws Exception {
        DPJob job = new DPJob();
        when(jobCreationService.readJobs(any())).thenReturn(Stream.of(job));
        doThrow(new DocPipeRunException("boom")).when(docPipeRunner).runForProject(def);
        // no manifest file on disk

        uut.onPipelineFinished(orchestrator, "p1", PipelineRun.root("p1"));

        verify(orchestrator, never()).scheduleEmbedding(anyString(), any());
    }

    @Test
    void readJobsIOException_skipsGracefully() throws Exception {
        when(jobCreationService.readJobs(any())).thenThrow(new IOException("disk on fire"));

        assertThatCode(() -> uut.onPipelineFinished(orchestrator, "p1", PipelineRun.root("p1")))
                .doesNotThrowAnyException();

        verify(docPipeRunner, never()).runForProject(any());
        verify(orchestrator, never()).scheduleEmbedding(anyString(), any());
    }

    @Test
    void missingDefinition_noOp() throws Exception {
        when(registry.definitionOf("p1")).thenReturn(null);

        uut.onPipelineFinished(orchestrator, "p1", PipelineRun.root("p1"));

        verify(jobCreationService, never()).readJobs(any());
        verify(docPipeRunner, never()).runForProject(any());
        verify(orchestrator, never()).scheduleEmbedding(anyString(), any());
    }
}
