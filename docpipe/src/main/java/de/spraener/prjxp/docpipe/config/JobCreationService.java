package de.spraener.prjxp.docpipe.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.docpipe.DPLogMessage;
import de.spraener.prjxp.docpipe.DPLogService;
import de.spraener.prjxp.docpipe.DocPipeConfig;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.model.DPJob;
import de.spraener.prjxp.docpipe.model.DPModelConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Log
public class JobCreationService {
    private final DocPipeConfig docPipeConfig;
    private final ObjectMapper objectMapper;
    private final DotDPFilesService dpFilesService;
    private final Validator validator;
    private final DPLogService logService;

    public Stream<DPJob> readJobs(Path rootDir) throws IOException {
        return Files.walk(rootDir)
                .map(Path::toFile)
                .filter(File::isDirectory)
                .filter(dpFilesService::hasDocPipeDir)
                .map(this::createDPJob)
                .filter(j->j!=null && j.getContentCreationList()!=null)
                ;
    }

    private DPJob createDPJob(File directory) {
        DPJob dpJob = new DPJob();
        dpJob.setRootDir(directory);

        try {
            File modelsJson = dpFilesService.getModelsJsonFrom(directory);
            if (modelsJson.exists()) {
                dpJob.setModelConfigs(this.objectMapper.readValue(modelsJson, new TypeReference<List<DPModelConfig>>(){}));
                Errors errors = validator.validateObject(dpJob.getModelConfigs());
                if(errors.hasErrors()) {
                    logService.logMessage(
                      new DPLogMessage(Level.SEVERE,   "Configuration-Errors ")
                    );
                    return DPJob.EMPTY_JOB;
                }
            } else if( !docPipeConfig.getGlobalModels().isEmpty() ) {
                log.fine("No models.json found for "+modelsJson.getAbsolutePath()+". Using global models.");
                dpJob.setModelConfigs(docPipeConfig.getGlobalModels());
            } else {
                log.warning("expected models.json does not exist: "+modelsJson.getAbsolutePath());
            }
        } catch (IOException e) {
            logService.logMessage(
                new DPLogMessage(Level.SEVERE, "Error reading models.json from " + directory.getAbsolutePath()+": "+e.getMessage())
            );
            return DPJob.EMPTY_JOB;
        }

        File documentsJson = dpFilesService.getDocumentsJsonFrom(directory);;
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
