package de.spraener.prjxp.docpipe;

import de.spraener.prjxp.docpipe.config.JobCreationService;
import de.spraener.prjxp.docpipe.content.ContentCreation;
import de.spraener.prjxp.docpipe.content.ContentCreationService;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.prompt.PromptResolvingService;
import de.spraener.prjxp.docpipe.llm.LLMService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class DocPipeRunner {
    private final JobCreationService jobCreationService;
    private final ContentCreationService contentCreationService;

    public void run(DocPipeConfig cfg) throws Exception {
        jobCreationService
                .readJobs(cfg.getProjectDir())
                .flatMap( dpj -> {
                    List<ContentCreation> contentCreationList = new ArrayList<>();
                    for(DPContentCreation cc : dpj.getContentCreationList() ) {
                        contentCreationList.add(new ContentCreation(dpj, cc));
                    }
                    return contentCreationList.stream();
                })
                .forEach(cc -> contentCreationService.createContent(cc));
    }
}
