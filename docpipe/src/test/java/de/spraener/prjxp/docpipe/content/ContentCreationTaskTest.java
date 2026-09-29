package de.spraener.prjxp.docpipe.content;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.model.DPJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class ContentCreationTaskTest {

    @TempDir
    java.nio.file.Path tempDir;

    private DPJob job() {
        DPJob job = new DPJob();
        job.setRootDir(tempDir.toFile());
        job.setPxCfg(mock(PrjXPConfig.class));
        job.setContentCreationList(List.of());
        return job;
    }

    private DPContentCreation creation(String outputFile) {
        DPContentCreation creation = new DPContentCreation();
        creation.setOutputFile(outputFile);
        return creation;
    }

    @Test
    void getters_returnConfiguredJobAndContentCreation() {
        DPJob job = job();
        DPContentCreation creation = creation("out.md");

        ContentCreationTask task = new ContentCreationTask(job, creation);

        assertThat(task.getDpJob()).isSameAs(job);
        assertThat(task.getDpContentCreation()).isSameAs(creation);
    }

    @Test
    void createContent_runsWithoutError() {
        ContentCreationTask task = new ContentCreationTask(job(), creation("out.md"));

        assertThatCode(task::createContent).doesNotThrowAnyException();
    }
}
