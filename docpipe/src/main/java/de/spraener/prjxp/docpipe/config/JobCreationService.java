package de.spraener.prjxp.docpipe.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.errorlog.PxLogMessage;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.model.DPJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.validation.Validator;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Log
/**
 * Service responsible for discovering and creating documentation jobs.
 * <p>
 * This service scans the project directory for {@code .docpipe} directories and parses
 * their {@code documents.json} files to create {@link DPJob} instances.
 * </p>
 */
public class JobCreationService {
    private final PrjXPConfig pxCfg;
    private final ObjectMapper objectMapper;
    private final DotDPFilesService dpFilesService;
    private final Validator validator;
    private final PxLogService logService;

    /**
     * Reads all documentation jobs from the given project definition.
     * <p>
     * This method walks the project root directory, identifies directories containing a {@code .docpipe}
     * folder, and converts each into a {@link DPJob}.
     * </p>
     *
     * @param pd the project definition containing the root directory
     * @return a stream of discovered documentation jobs
     * @throws IOException if an error occurs while walking the file system
     */
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

    /**
     * Creates a {@link DPJob} instance for the given directory.
     * <p>
     * This method reads the {@code documents.json} file within the directory to configure 
     * the content creation tasks for the job.
     * </p>
     *
     * @param directory the directory containing the {@code .docpipe} configuration
     * @return a configured {@link DPJob}, or {@link DPJob#EMPTY_JOB} if an error occurs during parsing
     */
    private DPJob createDPJob(Path directory) {
        DPJob dpJob = new DPJob();
        dpJob.setRootDir(directory.toFile());
        dpJob.setPxCfg(pxCfg);

        File documentsJson = dpFilesService.getDocumentsJsonFrom(directory.toFile());;
        try {
            if (documentsJson.exists()) {
                List< DPContentCreation> creations = this.objectMapper.readValue(documentsJson, new TypeReference<List<DPContentCreation>>() {});
                List<DPContentCreation> expandedCreations = expandCreations(dpJob,creations);
                dpJob.setContentCreationList(expandedCreations);
            }
        } catch( Exception e) {
            logService.error(e, "Error reading documents.json from %s: %s",documentsJson.getAbsolutePath(), e.getMessage());
            return DPJob.EMPTY_JOB;
        }
        return dpJob;
    }

    private List<DPContentCreation> expandCreations(DPJob dpJob, List<DPContentCreation> creations) {
        ArrayList<DPContentCreation> expandedCreations = new ArrayList<>();
        creations.forEach(c -> {
           expandedCreations.addAll(expandCreation(dpJob, c));
        });
        return expandedCreations;
    }

    private Collection<? extends DPContentCreation> expandCreation(DPJob dpJob, DPContentCreation c) {
        if( StringUtils.hasText(c.getForEach()) ) {
            Path rootPath = dpJob.getRootDir().toPath();
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + c.getForEach());
            List<DPContentCreation> result = new ArrayList<>();

            try (Stream<Path> stream = Files.walk(rootPath)) {
                stream.filter(Files::isRegularFile)
                      .filter(path -> matcher.matches(rootPath.relativize(path)))
                      .forEach(matchedFile -> {
                          DPContentCreation copy = c.clone(objectMapper);
                          String fileName = matchedFile.getFileName().toString();
                          String fqName = rootPath.relativize(matchedFile).toString();
                          copy.getArgs().put("currentFile", fqName);

                          String outputDir = ".";
                          if( StringUtils.hasText(c.getOutputDir()) ) {
                              outputDir = outputDir + "/" + c.getOutputDir();
                          }
                          String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
                          copy.setOutputFile(outputDir + "/" + baseName + c.getOutputFile());
                          if( StringUtils.hasText(c.getStorePrompt())) {
                              copy.setStorePrompt(outputDir + "/" + baseName + c.getStorePrompt());
                          }
                          result.add(copy);
                      });
            } catch (IOException e) {
                logService.error(e, "Error expanding forEach pattern %s in %s: %s", c.getForEach(), rootPath, e.getMessage());
            }

            return result;
        } else {
            return List.of(c);
        }
    }
}
