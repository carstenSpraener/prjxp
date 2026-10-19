package de.spraener.prjxp.docpipe;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.errorlog.PxLogMessage;
import de.spraener.prjxp.docpipe.config.DotDPFilesService;
import de.spraener.prjxp.docpipe.config.JobCreationService;
import de.spraener.prjxp.docpipe.config.ModelConfigLoader;
import de.spraener.prjxp.docpipe.content.ContentCreationService;
import de.spraener.prjxp.docpipe.content.ContentCreationTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
@Log
/**
 * Orchestrates the documentation pipeline process.
 * <p>
 * This class is responsible for discovering documentation jobs, creating individual content creation tasks,
 * and executing them in parallel using a fixed thread pool. It also handles the final error reporting
 * based on the log service's state.
 * </p>
 */
public class DocPipeRunner {
    @Value("${prjxp.docpipe.maxthreads:5}")
    private int maxThreads;
    private final JobCreationService jobCreationService;
    private final ContentCreationService contentCreationService;
    private final ModelConfigLoader modelConfigLoader;
    private final DotDPFilesService dpFilesService;
    private final de.spraener.prjxp.common.errorlog.PxLogService logService;

    private IntConsumer exitHandler = System::exit;

    /** Test seam: replaces the process-exit callback (defaults to {@code System::exit}). */
    void setExitHandler(IntConsumer exitHandler) { this.exitHandler = exitHandler; }

    /** Setter-injected (keeps the 5-arg constructor stable for existing tests). */
    private DocPipeManifestWriter manifestWriter;

    public void setManifestWriter(DocPipeManifestWriter manifestWriter) { this.manifestWriter = manifestWriter; }

    /**
     * Executes the documentation pipeline for the active project.
     * <p>
     * This method reads all available jobs, flattens them into a list of content creation tasks,
     * and submits these tasks to an executor service for parallel processing. If any severe errors
     * occurred during execution, it logs a summary and exits with status 1.
     * </p>
     *
     * @param cfg the project configuration containing the active project path
     * @throws Exception if an unexpected error occurs during job reading or execution
     */
    public void run(PrjXPConfig cfg) throws Exception {
        List<ContentCreationTask> allTasks = jobCreationService
                .readJobs(cfg.getActiveProject())
                .flatMap(dpj ->
                        dpj.getContentCreationList().stream()
                        .map(cc -> new ContentCreationTask(dpj, cc))
                )
                .collect(Collectors.toList());

        try (ExecutorService executor = Executors.newFixedThreadPool(maxThreads)) {
            for (ContentCreationTask task : allTasks) {
                executor.submit(() -> {
                    contentCreationService.createContent(task);
                });
            }
        }
        if( logService.maxLevel().intValue() >= Level.SEVERE.intValue() ) {
            log.severe("The run produced one or more errors! Here is a summary:");
            Stream<PxLogMessage> errorMessages = logService.getMessagesWithLevelMin(Level.SEVERE);
            errorMessages.forEach(msg -> log.severe(msg.getMessage()));
            exitHandler.accept(1);
        }
    }

    /**
     * Runs DocPipe for an explicitly given project (hub in-process use). Unlike {@link #run(PrjXPConfig)}
     * this NEVER calls System.exit: failures are reported by throwing {@link DocPipeRunException} after
     * the run. A manifest of generated documents is written (even on partial failure) so the hub
     * can embed whatever was produced.
     */
    public void runForProject(ProjectDefinition pd) throws Exception {
        List<ContentCreationTask> allTasks = jobCreationService
                .readJobs(Optional.of(pd))
                .flatMap(dpj -> dpj.getContentCreationList().stream()
                        .map(cc -> new ContentCreationTask(dpj, cc)))
                .collect(Collectors.toList());

        DocPipeRunResult result = new DocPipeRunResult();
        ExecutorService executor = Executors.newFixedThreadPool(maxThreads);
        try {
            for (ContentCreationTask task : allTasks) {
                executor.submit(() -> contentCreationService.createContent(task, result));
            }
            executor.shutdown();
            // Await completion: the manifest (and the hub's embedding run) must see all recorded
            // outputs. createContent never throws (it records failures), so this only times out
            // if a task hangs (e.g. an unresponsive LLM).
            if (!executor.awaitTermination(1, TimeUnit.HOURS)) {
                throw new DocPipeRunException("DocPipe run for project '" + pd.getName() + "' did not finish in time");
            }
        } finally {
            executor.shutdownNow();
        }

        if (manifestWriter != null) {
            manifestWriter.write(pd, result.outputs());   // always write (overwrite): the hub reads it after the run
        }

        if (result.hasFailures()) {
            String summary = result.failures().stream()
                    .map(f -> f.task() + ": " + f.message())
                    .collect(Collectors.joining("; "));
            throw new DocPipeRunException("DocPipe run for project '" + pd.getName() + "' had "
                    + result.failures().size() + " failure(s): " + summary);
        }
    }
}
