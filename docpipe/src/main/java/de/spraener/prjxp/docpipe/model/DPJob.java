package de.spraener.prjxp.docpipe.model;

import lombok.Data;

import java.io.File;
import java.util.List;
import java.util.Optional;

@Data
public class DPJob {
    public static final DPJob EMPTY_JOB = createEmptyJob();

    // This job is intended to be used in case of an miss configuration
    // in order to not stop the whole processing.
    private static DPJob createEmptyJob() {
        DPJob job = new DPJob();
        job.setModelConfigs(List.of());
        job.setContentCreationList(List.of());
        job.setRootDir(new File(""));
        return job;
    }

    private File rootDir;
    private List<DPModelConfig> modelConfigs;
    private List<DPContentCreation> contentCreationList;

    public Optional<DPModelConfig> getModelForStereotype(String stereotype) {
        return modelConfigs.stream()
                .filter(c -> c.getStereotype().equals(stereotype))
                .findFirst()
        ;
    }
}
