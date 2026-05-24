package de.spraener.prjxp.docpipe.content;

import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.model.DPJob;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;

@Data
@RequiredArgsConstructor
@Log
/**
 * Represents a single unit of work for creating documentation content.
 * <p>
 * This class pairs a {@link DPJob} with a specific {@link DPContentCreation} configuration,
 * providing the necessary context for the {@link ContentCreationService} to execute the task.
 * </p>
 */
public class ContentCreationTask {
    private final DPJob dpJob;
    private final DPContentCreation dpContentCreation;

    /**
     * Executes the content creation process for this task.
     */
    public void createContent() {
        log.info("Creating content for dpJob: " + dpContentCreation.getOutputFile());
    }
}
