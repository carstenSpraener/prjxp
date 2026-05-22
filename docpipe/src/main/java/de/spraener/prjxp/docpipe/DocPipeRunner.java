package de.spraener.prjxp.docpipe;

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

    public void run(DocPipeConfig cfg) throws Exception {
        try {
            String globalModelFileName = dpFilesService.globalModelsFileName(cfg);
            if( Files.exists(Path.of(globalModelFileName)) ) {
                cfg.setGlobalModels(modelConfigLoader.listFrom(globalModelFileName));
            }
        } catch( ConfigException ce ) {
            log.log(Level.SEVERE, "Error while reading global models.json. Check your configuration: "+ ce.getMessage(), ce);
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
    }
}
