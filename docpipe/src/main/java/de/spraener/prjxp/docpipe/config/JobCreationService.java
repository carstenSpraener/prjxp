package de.spraener.prjxp.docpipe.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.docpipe.DPLogMessage;
import de.spraener.prjxp.docpipe.DPLogService;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.model.DPJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;
import org.springframework.validation.Validator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Log
public class JobCreationService {
    private final PrjXPConfig pxCfg;
    private final ObjectMapper objectMapper;
    private final DotDPFilesService dpFilesService;
    private final Validator validator;
    private final DPLogService logService;

    public Stream<DPJob> readJobs(Optional<ProjectDefinition> pd) throws IOException {
        if( pd.isPresent() ) {
            Path rootPath = Path.of(pd.get().getRootDir());
            return Files.walk(rootPath)
                    .filter(Files::isDirectory)
                    .filter(dpFilesService::hasDocPipeDir)
                    .map(this::createDPJob)
                    .filter(j ->
                            j != null && j.getContentCreationList() != null
                    )
                    ;
        } else {
            return Stream.empty();
        }
    }

    private DPJob createDPJob(Path directory) {
        DPJob dpJob = new DPJob();
        dpJob.setRootDir(directory.toFile());
        dpJob.setPxCfg(pxCfg);

        File documentsJson = dpFilesService.getDocumentsJsonFrom(directory.toFile());;
        try {
            if (documentsJson.exists()) {
                List< DPContentCreation> creations = this.objectMapper.readValue(documentsJson, new TypeReference<List<DPContentCreation>>() {});
                dpJob.setContentCreationList(creations);
            }
        } catch( Exception e) {
            logService.logMessage(
                    new DPLogMessage(Level.SEVERE, "Error reading documents.json from " + documentsJson.getAbsolutePath()+": "+e.getMessage())
            );
            return DPJob.EMPTY_JOB;
        }
        return dpJob;
    }
}
