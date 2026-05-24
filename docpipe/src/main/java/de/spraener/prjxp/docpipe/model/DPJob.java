package de.spraener.prjxp.docpipe.model;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.config.PrjXPConfig;
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
        job.setContentCreationList(List.of());
        job.setRootDir(new File(""));
        PrjXPConfig dummyCfg = new PrjXPConfig();
        dummyCfg.setChatModels(List.of());
        job.setPxCfg(dummyCfg);
        return job;
    }

    private File rootDir;
    private PrjXPConfig pxCfg;
    private List<DPContentCreation> contentCreationList;

    public Optional<PrjXPChatModelReference> getModelForStereotype(String stereotype) {
        return pxCfg.getChatModels().stream()
                .filter(ref -> ref.getStereoType().equals(stereotype))
                .findFirst()
        ;
    }
}
