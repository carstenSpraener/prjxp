package de.spraener.prjxp.docpipe.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.model.DPJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.validation.Validator;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JobCreationServiceTest {

    private PrjXPConfig pxCfg;
    private Validator validator;
    private PxLogService logService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DotDPFilesService dpFilesService = new DotDPFilesService();

    private JobCreationService service;

    @BeforeEach
    void setUp() {
        pxCfg = mock(PrjXPConfig.class);
        validator = mock(Validator.class); // injected but currently unused (dead dependency)
        logService = mock(PxLogService.class);
        service = new JobCreationService(pxCfg, objectMapper, dpFilesService, validator, logService);
    }

    private File fixtureRoot() throws URISyntaxException {
        return new File(Objects.requireNonNull(
                getClass().getClassLoader().getResource("fixtures/dp-project")).toURI());
    }

    private ProjectDefinition pdFor(File dir) {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("dp-project");
        pd.setRootDir(dir.getAbsolutePath());
        return pd;
    }

    @Test
    void readJobs_emptyOptional_returnsEmptyStream() throws Exception {
        assertThat(service.readJobs(Optional.empty()).count()).isZero();
    }

    @Test
    void readJobs_projectWithoutDpDir_returnsEmptyStream(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src"));

        assertThat(service.readJobs(Optional.of(pdFor(tempDir.toFile()))).count()).isZero();
    }

    @Test
    void readJobs_nonexistentRootDir_throwsNoSuchFileException(@TempDir Path tempDir) {
        ProjectDefinition pd = pdFor(tempDir.resolve("missing").toFile());

        assertThatThrownBy(() -> service.readJobs(Optional.of(pd)).count())
                .isInstanceOf(NoSuchFileException.class);
    }

    @Test
    void readJobs_fixtureProject_createsSingleJobWithExpandedCreations() throws Exception {
        File root = fixtureRoot();

        List<DPJob> jobs = service.readJobs(Optional.of(pdFor(root))).toList();

        assertThat(jobs).hasSize(1);
        DPJob job = jobs.get(0);
        assertThat(job.getRootDir()).isEqualTo(root);
        assertThat(job.getPxCfg()).isSameAs(pxCfg);

        // 2 java files + 1 txt file (glob) + 1 plain entry; the **/*.xyz glob matches nothing
        assertThat(job.getContentCreationList()).hasSize(4);

        DPContentCreation hello = byCurrentFile(job, "src/Hello.java");
        assertThat(hello).isNotNull();
        assertThat(hello.getOutputFile()).isEqualTo("./Hello.md");
        assertThat(hello.getStorePrompt()).isNull();
        assertThat(hello.getStereotype()).isEqualTo("doc-writer");

        DPContentCreation world = byCurrentFile(job, "src/sub/World.java");
        assertThat(world).isNotNull();
        assertThat(world.getOutputFile()).isEqualTo("./World.md");

        DPContentCreation readme = byCurrentFile(job, "notes/readme.txt");
        assertThat(readme).isNotNull();
        // outputDir + storePrompt are honoured on the expanded copy
        assertThat(readme.getOutputFile()).isEqualTo("./docs/readme.md");
        assertThat(readme.getStorePrompt()).isEqualTo("./docs/readme.prompt.txt");

        DPContentCreation plain = job.getContentCreationList().stream()
                .filter(c -> c.getForEach() == null)
                .findFirst().orElse(null);
        assertThat(plain).isNotNull();
        assertThat(plain.getOutputFile()).isEqualTo("README.md");
        assertThat(plain.getArgs()).isEmpty();
    }

    private DPContentCreation byCurrentFile(DPJob job, String currentFile) {
        return job.getContentCreationList().stream()
                .filter(c -> c.getArgs() != null && currentFile.equals(c.getArgs().get("currentFile")))
                .findFirst().orElse(null);
    }

    @Test
    void readJobs_dpDirWithoutDocumentsJson_jobIsFilteredOut(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve(".dp"));

        assertThat(service.readJobs(Optional.of(pdFor(tempDir.toFile()))).count()).isZero();
    }

    @Test
    void readJobs_emptyDocumentsJson_jobWithEmptyCreationList(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve(".dp"));
        Files.writeString(tempDir.resolve(".dp/documents.json"), "[]");

        List<DPJob> jobs = service.readJobs(Optional.of(pdFor(tempDir.toFile()))).toList();

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getContentCreationList()).isEmpty();
    }

    @Test
    void readJobs_invalidDocumentsJson_returnsEmptyJobAndLogsError(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve(".dp"));
        Files.writeString(tempDir.resolve(".dp/documents.json"), "{ this is not valid json !!!");

        List<DPJob> jobs = service.readJobs(Optional.of(pdFor(tempDir.toFile()))).toList();

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0)).isSameAs(DPJob.EMPTY_JOB);
        verify(logService).error(any(Throwable.class), contains("documents.json"), any(), any());
    }

    @Test
    void readJobs_multipleDpDirs_createsOneJobPerDir(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve(".dp"));
        Files.writeString(tempDir.resolve(".dp/documents.json"), "[]");
        Path nested = tempDir.resolve("module/.dp");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("documents.json"), "[]");

        List<DPJob> jobs = service.readJobs(Optional.of(pdFor(tempDir.toFile()))).toList();

        assertThat(jobs).hasSize(2);
        assertThat(jobs)
                .extracting(DPJob::getRootDir)
                .contains(tempDir.toFile(), tempDir.resolve("module").toFile());
    }
}
