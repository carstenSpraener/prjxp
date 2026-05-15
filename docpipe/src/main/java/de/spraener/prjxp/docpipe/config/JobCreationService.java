package de.spraener.prjxp.docpipe.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Log
public class JobCreationService {
    private final ObjectMapper objectMapper;

    public Stream<DPJob> readJobs(Path rootDir) throws IOException {
        return Files.walk(rootDir)
                .map(Path::toFile)
                .filter(File::isDirectory)
                .filter(d -> new File(d.getAbsolutePath() + "/.contentCreation").exists())
                .map(this::createDPJob)
                .filter(j->j!=null)
                ;
    }

    private DPJob createDPJob(File directory) {
        DPJob dpJob = new DPJob();
        dpJob.setRootDir(directory);
        File configDir = new File(directory.getAbsolutePath() + "/.contentCreation");

        try {
            File modelsJson = new File(configDir.getAbsolutePath() + "/models.json");
            if (modelsJson.exists()) {
                dpJob.setModelConfigs(this.objectMapper.readValue(modelsJson, new TypeReference<List<DPModelConfig>>(){}));
            } else {
                log.warning("No model configs found for "+configDir.getAbsolutePath());
            }
        } catch (Exception e) {
            log.severe("Error reading models.json from " + directory.getAbsolutePath());
            return null;
        }

        try {
            File documentsJson = new File(configDir.getAbsolutePath() +"/documents.json");
            if (documentsJson.exists()) {
                List< DPContentCreation> creations = this.objectMapper.readValue(documentsJson, new TypeReference<List<DPContentCreation>>() {});
                dpJob.setContentCreationList(creations);
            }
        } catch( Exception e) {
            log.severe("Error reading documents.json from " + directory.getAbsolutePath());
            return null;
        }
        return dpJob;
    }
}
