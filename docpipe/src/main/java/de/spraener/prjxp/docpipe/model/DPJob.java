package de.spraener.prjxp.docpipe.model;

import lombok.Data;

import java.io.File;
import java.util.List;

@Data
public class DPJob {
    private File rootDir;
    private List<DPModelConfig> modelConfigs;
    private List<DPContentCreation> contentCreationList;

    public DPModelConfig getModelForStereotype(String stereotype) {
        return modelConfigs.stream()
                .filter(c -> c.getStereotype().equals(stereotype))
                .findFirst()
                .orElse(null);
    }
}
