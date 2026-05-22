package de.spraener.prjxp.docpipe;

import de.spraener.prjxp.common.util.ValueContainer;
import de.spraener.prjxp.docpipe.config.ConfigException;
import de.spraener.prjxp.docpipe.config.DotDPFilesService;
import de.spraener.prjxp.docpipe.config.JobCreationService;
import de.spraener.prjxp.docpipe.config.ModelConfigLoader;
import de.spraener.prjxp.docpipe.content.ContentCreationTask;
import de.spraener.prjxp.docpipe.content.ContentCreationService;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
@Log
public class DocPipeRunner {
    @Value("${prjxp.docpipe.maxthreads:5}")
    private int maxThreads;
    private final JobCreationService jobCreationService;
    private final ContentCreationService contentCreationService;
    private final ModelConfigLoader modelConfigLoader;
    private final DotDPFilesService dpFilesService;
    private final DPLogService logService;

    public void run(DocPipeConfig cfg) throws Exception {
        try {
            String globalModelFileName = dpFilesService.globalModelsFileName(cfg);
            if( Files.exists(Path.of(globalModelFileName)) ) {
                cfg.setGlobalModels(modelConfigLoader.listFrom(globalModelFileName));
            }
        } catch( ConfigException ce ) {
            logService.logMessage(
               new DPLogMessage(Level.SEVERE, "Error while reading global models.json. Check your configuration: "+ ce.getMessage())
            );
            return;
        }

        List<ContentCreationTask> allTasks = jobCreationService
                .readJobs(cfg.getProjectDir())
                .flatMap(dpj -> dpj.getContentCreationList().stream()
                        .map(cc -> new ContentCreationTask(dpj, cc)))
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
            Stream<DPLogMessage> errorMessages = logService.getMessagesWithLevelMin(Level.SEVERE);
            errorMessages.forEach(msg -> log.severe(msg.getMessage()));
            System.exit(1);
        }
    }
}
