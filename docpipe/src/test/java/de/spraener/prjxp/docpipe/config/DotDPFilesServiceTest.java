package de.spraener.prjxp.docpipe.config;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.docpipe.DocPipeConfig;
import de.spraener.prjxp.docpipe.content.ContentCreationTask;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.model.DPJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DotDPFilesServiceTest {

    private final DotDPFilesService service = new DotDPFilesService();

    @TempDir
    Path tempDir;

    private ContentCreationTask taskWithRoot(Path root) {
        DPJob job = new DPJob();
        job.setRootDir(root.toFile());
        job.setPxCfg(mock(PrjXPConfig.class));

        DPContentCreation creation = new DPContentCreation();
        creation.setOutputFile("output.md");

        return new ContentCreationTask(job, creation);
    }

    @Test
    void dotPipeDir_appendsDpToJobRoot() {
        ContentCreationTask task = taskWithRoot(tempDir);

        File result = service.dotPipeDir(task);

        assertThat(result.toPath().toAbsolutePath().normalize())
                .isEqualTo(tempDir.resolve(DotDPFilesService.DP_DIR).toAbsolutePath().normalize());
    }

    @Test
    void globalModelsFileName_appendsDpAndModelsJsonToProjectDir() {
        DocPipeConfig cfg = new DocPipeConfig();
        cfg.setProjectDir(tempDir);

        String result = service.globalModelsFileName(cfg);

        assertThat(Path.of(result).toAbsolutePath().normalize())
                .isEqualTo(tempDir.resolve(DotDPFilesService.DP_DIR).resolve("models.json").toAbsolutePath().normalize());
    }

    @Test
    void hasDocPipeDir_trueWhenDpFolderExists() throws Exception {
        Files.createDirectories(tempDir.resolve(DotDPFilesService.DP_DIR));

        assertThat(service.hasDocPipeDir(tempDir)).isTrue();
    }

    @Test
    void hasDocPipeDir_falseWhenDpFolderMissing() {
        assertThat(service.hasDocPipeDir(tempDir)).isFalse();
    }

    @Test
    void getDotPipeDir_appendsDpToDirectory() {
        File result = service.getDotPipeDir(tempDir.toFile());

        assertThat(result.toPath().toAbsolutePath().normalize())
                .isEqualTo(tempDir.resolve(DotDPFilesService.DP_DIR).toAbsolutePath().normalize());
    }

    @Test
    void getModelsJsonFrom_appendsDpAndModelsJson() {
        File result = service.getModelsJsonFrom(tempDir.toFile());

        assertThat(result.getName()).isEqualTo("models.json");
        assertThat(result.getParentFile().getName()).isEqualTo(DotDPFilesService.DP_DIR);
        assertThat(result.toPath().toAbsolutePath().normalize())
                .isEqualTo(tempDir.resolve(DotDPFilesService.DP_DIR).resolve("models.json").toAbsolutePath().normalize());
    }

    @Test
    void getDocumentsJsonFrom_appendsDpAndDocumentsJson() {
        File result = service.getDocumentsJsonFrom(tempDir.toFile());

        assertThat(result.getName()).isEqualTo("documents.json");
        assertThat(result.getParentFile().getName()).isEqualTo(DotDPFilesService.DP_DIR);
    }

    @Test
    void getContentHashesFrom_fileVersion_appendsDpAndPropertiesFile() {
        File result = service.getContentHashesFrom(tempDir.toFile());

        assertThat(result.getName()).isEqualTo("content-hashes.properties");
        assertThat(result.getParentFile().getName()).isEqualTo(DotDPFilesService.DP_DIR);
    }

    @Test
    void getContentHashesFrom_taskVersion_delegatesToJobRoot() {
        ContentCreationTask task = taskWithRoot(tempDir);

        File result = service.getContentHashesFrom(task);

        assertThat(result.toPath().toAbsolutePath().normalize())
                .isEqualTo(tempDir.resolve(DotDPFilesService.DP_DIR).resolve("content-hashes.properties").toAbsolutePath().normalize());
    }

    @Test
    void getOutputFilePath_concatenatesJobRootAndOutputFile() {
        ContentCreationTask task = taskWithRoot(tempDir);

        String result = service.getOutputFilePath(task);

        assertThat(Path.of(result).toAbsolutePath().normalize())
                .isEqualTo(tempDir.resolve("output.md").toAbsolutePath().normalize());
    }
}
