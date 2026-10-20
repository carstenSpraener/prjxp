package de.spraener.prjxp.docpipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.docpipe.config.DotDPFilesService;
import de.spraener.prjxp.docpipe.config.JobCreationService;
import de.spraener.prjxp.docpipe.config.ModelConfigLoader;
import de.spraener.prjxp.docpipe.content.ContentCreationService;
import de.spraener.prjxp.docpipe.content.ContentCreationTask;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.model.DPJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DocPipeRunner#runForProject(ProjectDefinition)} — the in-process hub variant:
 * no System.exit, failures throw {@link DocPipeRunException}, and a manifest is written (even on
 * partial failure) via the real {@link DocPipeManifestWriter}.
 * <p>
 * Mirrors the construction pattern of {@link DocPipeRunnerTest}: the {@code @Value}-injected
 * {@code maxThreads} field is set via {@link ReflectionTestUtils}.
 */
class DocPipeRunnerForProjectTest {

    @TempDir
    Path tempDir;

    private JobCreationService jobCreationService;
    private ContentCreationService contentCreationService;
    private ModelConfigLoader modelConfigLoader;
    private DotDPFilesService dpFilesService;
    private PxLogService logService;

    private DocPipeRunner uut;
    private ProjectDefinition pd;

    @BeforeEach
    void setUp() {
        jobCreationService = mock(JobCreationService.class);
        contentCreationService = mock(ContentCreationService.class);
        modelConfigLoader = mock(ModelConfigLoader.class);
        dpFilesService = mock(DotDPFilesService.class);
        logService = mock(PxLogService.class);

        uut = new DocPipeRunner(jobCreationService, contentCreationService, modelConfigLoader,
                dpFilesService, logService);
        ReflectionTestUtils.setField(uut, "maxThreads", 2);

        pd = new ProjectDefinition();
        pd.setName("p");
        pd.setRootDir(tempDir.toString());
    }

    private DPJob job(String... outputFiles) {
        DPJob job = new DPJob();
        job.setRootDir(tempDir.toFile());
        List<DPContentCreation> creations = new ArrayList<>();
        for (String out : outputFiles) {
            DPContentCreation cc = new DPContentCreation();
            cc.setOutputFile(out);
            creations.add(cc);
        }
        job.setContentCreationList(creations);
        return job;
    }

    private String[] readManifest() throws Exception {
        Path manifest = tempDir.resolve(DocPipeManifestWriter.MANIFEST_RELATIVE_PATH);
        assertThat(manifest).exists();
        return new ObjectMapper().readValue(manifest.toFile(), String[].class);
    }

    @Test
    void successWritesManifestNoException() throws Exception {
        when(jobCreationService.readJobs(any())).thenReturn(Stream.of(job("docs/x.md")));
        doAnswer(inv -> {
            ((DocPipeRunResult) inv.getArgument(1)).recordOutput(tempDir.resolve("docs/x.md").toString());
            return null;
        }).when(contentCreationService).createContent(any(ContentCreationTask.class), any(DocPipeRunResult.class));
        uut.setManifestWriter(new DocPipeManifestWriter(new ObjectMapper()));

        assertThatCode(() -> uut.runForProject(pd)).doesNotThrowAnyException();

        assertThat(readManifest()).containsExactly("docs/x.md");
    }

    @Test
    void failureThrowsButManifestStillWritten() throws Exception {
        when(jobCreationService.readJobs(any())).thenReturn(Stream.of(job("docs/x.md")));
        doAnswer(inv -> {
            DocPipeRunResult result = inv.getArgument(1);
            result.recordOutput(tempDir.resolve("docs/x.md").toString());
            result.recordFailure("docs/y.md", "llm down");
            return null;
        }).when(contentCreationService).createContent(any(ContentCreationTask.class), any(DocPipeRunResult.class));
        uut.setManifestWriter(new DocPipeManifestWriter(new ObjectMapper()));

        assertThatThrownBy(() -> uut.runForProject(pd))
                .isInstanceOf(DocPipeRunException.class)
                .hasMessageContaining("docs/y.md");

        assertThat(readManifest()).containsExactly("docs/x.md");
    }

    @Test
    void noJobsNoManifestContent() throws Exception {
        when(jobCreationService.readJobs(any())).thenReturn(Stream.empty());
        // no manifestWriter set (null) — nothing may be written

        assertThatCode(() -> uut.runForProject(pd)).doesNotThrowAnyException();

        assertThat(tempDir.resolve(DocPipeManifestWriter.MANIFEST_RELATIVE_PATH)).doesNotExist();
    }
}
