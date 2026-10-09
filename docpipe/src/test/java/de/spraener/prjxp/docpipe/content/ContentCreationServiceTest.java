package de.spraener.prjxp.docpipe.content;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.docpipe.config.DotDPFilesService;
import de.spraener.prjxp.docpipe.io.OutputSink;
import de.spraener.prjxp.docpipe.io.OutputSinkFactory;
import de.spraener.prjxp.docpipe.llm.LLMService;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.model.DPJob;
import de.spraener.prjxp.docpipe.prompt.PromptResolvingService;
import de.spraener.prjxp.docpipe.prompt.TemplateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ContentCreationService} with all dependencies mocked.
 * The real (pure path-computation) {@link DotDPFilesService} is used as-is.
 * Error paths are verified through the mocked {@link PxLogService}, since all
 * exceptions inside {@code createContent} are swallowed and logged.
 */
class ContentCreationServiceTest {

    @TempDir
    Path tempDir;

    private PromptResolvingService promptResolvingService;
    private LLMService llmService;
    private ContentUpdateRequiredController updater;
    private OutputSinkFactory outputSinkFactory;
    private PxLogService logService;

    private ContentCreationService service;

    @BeforeEach
    void setUp() {
        promptResolvingService = mock(PromptResolvingService.class);
        llmService = mock(LLMService.class);
        updater = mock(ContentUpdateRequiredController.class);
        outputSinkFactory = mock(OutputSinkFactory.class);
        logService = mock(PxLogService.class);

        service = new ContentCreationService(promptResolvingService, llmService, updater,
                outputSinkFactory, new DotDPFilesService(), logService, List.of(new NoSurroundingCodeBlock()));
    }

    private ContentCreationTask task(String outputFile, String filterList, String ps) {
        DPJob job = new DPJob();
        job.setRootDir(tempDir.toFile());
        job.setPxCfg(mock(PrjXPConfig.class));

        DPContentCreation creation = new DPContentCreation();
        creation.setOutputFile(outputFile);
        creation.setFilterList(filterList);
        creation.setPs(ps);

        return new ContentCreationTask(job, creation);
    }

    /** Makes the mocked controller actually execute the updater lambda. */
    private void runUpdater() {
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(2)).run();
            return null;
        }).when(updater).onUpdateRequired(anyString(), any(ContentCreationTask.class), any(Runnable.class));
    }

    private OutputSink stubSink() throws IOException {
        OutputSink sink = mock(OutputSink.class);
        when(outputSinkFactory.createSink(any(Path.class))).thenReturn(sink);
        return sink;
    }

    @Test
    void createContent_appliesFilterAndAppendsPsSuffix() throws Exception {
        ContentCreationTask task = task("out.md", "noSurroundingCodeBlock", "PS");
        when(promptResolvingService.resolve(task)).thenReturn("RESOLVED PROMPT");
        runUpdater();
        when(llmService.chat(task, "RESOLVED PROMPT")).thenReturn("```java\ncode\n```");
        OutputSink sink = stubSink();

        service.createContent(task);

        verify(llmService).chat(task, "RESOLVED PROMPT");
        // filter strips the fences ("code\n"), then the ps suffix is appended
        verify(sink).println("code\nPS");
        verify(sink).close();
    }

    @Test
    void createContent_withoutFilterAndPs_writesRawLlmOutput() throws Exception {
        ContentCreationTask task = task("out.md", null, null);
        when(promptResolvingService.resolve(task)).thenReturn("PROMPT");
        runUpdater();
        when(llmService.chat(task, "PROMPT")).thenReturn("RAW CONTENT");
        OutputSink sink = stubSink();

        service.createContent(task);

        verify(sink).println("RAW CONTENT");
    }

    @Test
    void createContent_unknownFilterNameInList_isIgnored() throws Exception {
        ContentCreationTask task = task("out.md", "noSurroundingCodeBlock,unknownFilter", null);
        when(promptResolvingService.resolve(task)).thenReturn("PROMPT");
        runUpdater();
        when(llmService.chat(task, "PROMPT")).thenReturn("```java\ncode\n```");
        OutputSink sink = stubSink();

        service.createContent(task);

        // the known filter is applied, the unknown one is silently skipped
        verify(sink).println("code\n");
    }

    @Test
    void createContent_sinkCreationFails_logsErrorAndSwallowsException() throws Exception {
        ContentCreationTask task = task("out.md", null, null);
        when(promptResolvingService.resolve(task)).thenReturn("PROMPT");
        runUpdater();
        when(llmService.chat(task, "PROMPT")).thenReturn("CONTENT");
        IOException io = new IOException("disk on fire");
        when(outputSinkFactory.createSink(any(Path.class))).thenThrow(io);

        assertThatCode(() -> service.createContent(task)).doesNotThrowAnyException();

        verify(logService).error(io, "Error while trying to create content for %s: %s",
                task.getDpJob().getRootDir().getAbsolutePath(), "disk on fire");
    }

    @Test
    void createContent_resolveThrowsTemplateException_logsError() throws Exception {
        ContentCreationTask task = task("out.md", null, null);
        when(promptResolvingService.resolve(task)).thenThrow(new TemplateException("bad template"));

        assertThatCode(() -> service.createContent(task)).doesNotThrowAnyException();

        verify(logService).error(any(TemplateException.class), eq("Error while trying to create prompt for %s: %s"),
                eq(task.getDpJob().getRootDir().getAbsolutePath()), eq("bad template"));
    }

    @Test
    void createContent_resolveThrowsIOException_logsError() throws Exception {
        ContentCreationTask task = task("out.md", null, null);
        when(promptResolvingService.resolve(task)).thenThrow(new IOException("template file missing"));

        assertThatCode(() -> service.createContent(task)).doesNotThrowAnyException();

        verify(logService).error(any(IOException.class), eq("Error while trying to create prompt for %s: %s"),
                eq(task.getDpJob().getRootDir().getAbsolutePath()), eq("template file missing"));
    }

    @Test
    void createContent_unexpectedThrowable_logsError() throws Exception {
        ContentCreationTask task = task("out.md", null, null);
        when(promptResolvingService.resolve(task)).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> service.createContent(task)).doesNotThrowAnyException();

        verify(logService).error(any(IllegalStateException.class),
                eq("Unexpected throwable while creating content %s: %s"),
                eq(task.getDpJob().getRootDir().getAbsolutePath() + "/out.md"), eq("boom"));
    }
}
