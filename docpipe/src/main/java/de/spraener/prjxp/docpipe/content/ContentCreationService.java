package de.spraener.prjxp.docpipe.content;

import de.spraener.prjxp.docpipe.config.DotDPFilesService;
import de.spraener.prjxp.docpipe.io.OutputSink;
import de.spraener.prjxp.docpipe.io.OutputSinkFactory;
import de.spraener.prjxp.docpipe.llm.LLMService;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.prompt.PromptResolvingService;
import de.spraener.prjxp.docpipe.prompt.TemplateException;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;

@Service
@RequiredArgsConstructor
@Log
public class ContentCreationService {
    private final PromptResolvingService promptResolvingService;
    private final LLMService llmService;
    private final ContentUpdateRequiredController updater;
    private final OutputSinkFactory outputSinkFactory;
    private final DotDPFilesService dpFilesService;

    public void createContent(ContentCreationTask ccTask) {
        try {
            DPContentCreation dpcc = ccTask.getDpContentCreation();

            String prompt = promptResolvingService.resolve(ccTask);
            updater.onUpdateRequired(prompt, ccTask, () -> {
                log.info("Creating content of " + dpcc.getOutputFile());
                String content = llmService.chat(ccTask, prompt);
                if (StringUtils.hasText(dpcc.getPs())) {
                    content = content + ccTask.getDpContentCreation().getPs();
                }
                String outputFile = dpFilesService.getOutputFilePath(ccTask);
                try (OutputSink sink = outputSinkFactory.createSink(Path.of(outputFile))) {
                    sink.println(content);
                } catch (IOException e) {
                    log.log(Level.SEVERE, "Error while trying to create content for " + ccTask.getDpJob().getRootDir().getAbsolutePath() + ": " + e.getMessage(), e);
                }
            });
        } catch (TemplateException | IOException e) {
            log.log(Level.SEVERE, "Error while trying to create prompt for " + ccTask.getDpJob().getRootDir().getAbsolutePath() + ": " + e.getMessage(), e);
        }
    }
}
