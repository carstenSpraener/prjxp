package de.spraener.prjxp.docpipe;

import de.spraener.prjxp.docpipe.config.ConfigException;
import de.spraener.prjxp.docpipe.config.JobCreationService;
import de.spraener.prjxp.docpipe.config.ModelConfigLoader;
import de.spraener.prjxp.docpipe.content.ContentCreationTask;
import de.spraener.prjxp.docpipe.content.ContentCreationService;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

@Component
@RequiredArgsConstructor
@Log
public class DocPipeRunner {
    private final JobCreationService jobCreationService;
    private final ContentCreationService contentCreationService;
    private final ModelConfigLoader modelConfigLoader;

    public void run(DocPipeConfig cfg) throws Exception {
        try {
            String globalModelFileName = cfg.getProjectDir() + "/" + DocPipeConfig.DP_DIR + "/models.json";
            if( Files.exists(Path.of(globalModelFileName)) ) {
                cfg.setGlobalModels(modelConfigLoader.listFrom(globalModelFileName));
            }
        } catch( ConfigException ce ) {
            log.log(Level.SEVERE, "Error while reading global models.json. Check your configuration: "+ ce.getMessage(), ce);
            return;
        }

        jobCreationService
                .readJobs(cfg.getProjectDir())
                .flatMap( dpj -> {
                    List<ContentCreationTask> contentCreationList = new ArrayList<>();
                    for(DPContentCreation cc : dpj.getContentCreationList() ) {
                        contentCreationList.add(new ContentCreationTask(dpj, cc));
                    }
                    return contentCreationList.stream();
                })
                .forEach(cc -> contentCreationService.createContent(cc));
    }
}
