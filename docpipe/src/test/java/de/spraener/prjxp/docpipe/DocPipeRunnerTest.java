package de.spraener.prjxp.docpipe;

import de.spraener.prjxp.common.config.PrjXPConfig;
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
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DocPipeRunner}. Plain JUnit 5 + Mockito, no Spring context.
 * <p>
 * The {@code maxThreads} field is a plain {@code @Value}-annotated (non-final) field,
 * so it is injected via {@link ReflectionTestUtils} instead of the constructor.
 * <p>
 * NOTE: The {@code logService.maxLevel() >= SEVERE} branch (which ends in
 * {@code System.exit(1)}) is deliberately NOT tested — no SecurityManager or other
 * exit tricks. That path stays intentionally uncovered by design.
 */
class DocPipeRunnerTest {

    private JobCreationService jobCreationService;
    private ContentCreationService contentCreationService;
    private ModelConfigLoader modelConfigLoader;
    private DotDPFilesService dpFilesService;
    private PxLogService logService;

    @BeforeEach
    void setUp() {
        jobCreationService = mock(JobCreationService.class);
        contentCreationService = mock(ContentCreationService.class);
        modelConfigLoader = mock(ModelConfigLoader.class);
        dpFilesService = mock(DotDPFilesService.class);
        logService = mock(PxLogService.class);
    }

    private DocPipeRunner runner(int maxThreads) {
        DocPipeRunner uut = new DocPipeRunner(
                jobCreationService, contentCreationService, modelConfigLoader, dpFilesService, logService);
        ReflectionTestUtils.setField(uut, "maxThreads", maxThreads);
        return uut;
    }

    private PrjXPConfig cfg() {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("test-project");
        PrjXPConfig cfg = new PrjXPConfig();
        cfg.setActiveProject("test-project");
        cfg.setProjects(List.of(pd));
        return cfg;
    }

    private DPJob job(File rootDir, String... outputFiles) {
        DPJob job = new DPJob();
        job.setRootDir(rootDir);
        job.setPxCfg(cfg());
        List<DPContentCreation> creations = new java.util.ArrayList<>();
        for (String out : outputFiles) {
            DPContentCreation cc = new DPContentCreation();
            cc.setOutputFile(out);
            creations.add(cc);
        }
        job.setContentCreationList(creations);
        return job;
    }

    @Test
    void run_happyPath_submitsEveryContentCreationToExecutor() throws Exception {
        File root = new File("some-project");
        DPJob job1 = job(root, "a.md", "b.md");
        DPJob job2 = job(new File("other-project"), "c.md");

        when(jobCreationService.readJobs(any())).thenReturn(Stream.of(job1, job2));
        // below SEVERE -> the System.exit branch must NOT be entered
        when(logService.maxLevel()).thenReturn(Level.INFO);

        assertThatCode(() -> runner(2).run(cfg())).doesNotThrowAnyException();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ContentCreationTask> captor = ArgumentCaptor.forClass(ContentCreationTask.class);
        verify(contentCreationService, times(3)).createContent(captor.capture());

        List<DPJob> jobs = captor.getAllValues().stream()
                .map(ContentCreationTask::getDpJob).toList();
        List<String> outputs = captor.getAllValues().stream()
                .map(t -> t.getDpContentCreation().getOutputFile()).toList();

        // completion order on the thread pool is not deterministic
        assertThat(jobs).containsExactlyInAnyOrder(job1, job1, job2);
        assertThat(outputs).containsExactlyInAnyOrder("a.md", "b.md", "c.md");

        verify(logService, times(1)).maxLevel();
    }

    @Test
    void run_noJobs_neverCreatesContent() throws Exception {
        when(jobCreationService.readJobs(any())).thenReturn(Stream.empty());
        when(logService.maxLevel()).thenReturn(Level.INFO);

        assertThatCode(() -> runner(2).run(cfg())).doesNotThrowAnyException();

        verify(contentCreationService, never()).createContent(any());
    }

    @Test
    void run_jobWithoutCreations_producesNoTasks() throws Exception {
        DPJob empty = job(new File("empty-project"));

        when(jobCreationService.readJobs(any())).thenReturn(Stream.of(empty));
        when(logService.maxLevel()).thenReturn(Level.INFO);

        assertThatCode(() -> runner(1).run(cfg())).doesNotThrowAnyException();

        verify(contentCreationService, never()).createContent(any());
    }
}
