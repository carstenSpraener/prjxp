package de.spraener.prjxp.docpipe.model;

import de.spraener.prjxp.common.config.PrjXPChatModelReference;
import de.spraener.prjxp.common.config.PrjXPConfig;
import lombok.Data;

import java.io.File;
import java.util.List;
import java.util.Optional;

@Data
/**
 * Represents a documentation job configuration.
 * <p>
 * A job consists of a root directory, the project configuration (including LLM model mappings),
 * and a list of content creation tasks to be performed.
 * </p>
 */
public class DPJob {
    public static final DPJob EMPTY_JOB = createEmptyJob();

    // This job is intended to be used in case of an miss configuration
    // in order to not stop the whole processing.
    /**
     * Creates an empty job instance to be used as a fallback in case of misconfiguration.
     *
     * @return an empty {@link DPJob} instance with no content creation tasks and a dummy configuration
     */
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

    /**
     * Returns the chat model reference associated with the given stereotype.
     *
     * @param stereotype the name of the model stereotype to look up
     * @return an Optional containing the matching chat model reference, or empty if not found
     */
    public Optional<PrjXPChatModelReference> getModelForStereotype(String stereotype) {
        return pxCfg.getChatModels().stream()
                .filter(ref -> ref.getStereoType().equals(stereotype))
                .findFirst()
        ;
    }
}
