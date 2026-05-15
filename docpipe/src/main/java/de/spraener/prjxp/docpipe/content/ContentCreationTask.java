package de.spraener.prjxp.docpipe.content;

import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.model.DPJob;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;

@Data
@RequiredArgsConstructor
@Log
public class ContentCreationTask {
    private final DPJob dpJob;
    private final DPContentCreation dpContentCreation;

    public void createContent() {
        log.info("Creating content for dpJob: " + dpContentCreation.getOutputFile());
    }
}
