package de.spraener.prjxp.docpipe.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.docpipe.DocPipeConfig;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.model.DPJob;
import de.spraener.prjxp.docpipe.model.DPModelConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

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
            } else if( !docPipeConfig.getGlobalModels().isEmpty() ) {
                log.fine("No models.json found for "+modelsJson.getAbsolutePath()+". Using global models.");
                dpJob.setModelConfigs(docPipeConfig.getGlobalModels());
            } else {
                log.warning("expected models.json does not exist: "+modelsJson.getAbsolutePath());
            }
        } catch (IOException e) {
            log.log(Level.SEVERE, "Error reading models.json from " + directory.getAbsolutePath(), e);
            return DPJob.EMPTY_JOB;
        }

        File documentsJson = dpFilesService.getDocumentsJsonFrom(directory);;
        try {
            if (documentsJson.exists()) {
                List< DPContentCreation> creations = this.objectMapper.readValue(documentsJson, new TypeReference<List<DPContentCreation>>() {});
                dpJob.setContentCreationList(creations);
            }
        } catch( Exception e) {
            log.severe("Error reading documents.json from " + documentsJson.getAbsolutePath()+": "+e.getMessage());
            return DPJob.EMPTY_JOB;
        }
        return dpJob;
    }
}
