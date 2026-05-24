package de.spraener.prjxp.docpipe.content;

import de.spraener.prjxp.docpipe.DPLogMessage;
import de.spraener.prjxp.docpipe.DPLogService;
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
import java.util.List;
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
    private final DPLogService logService;
    private final List<ContentFilter> contentFilterList;

    public void createContent(ContentCreationTask ccTask) {
        try {
            DPContentCreation dpcc = ccTask.getDpContentCreation();

            String prompt = promptResolvingService.resolve(ccTask);
            updater.onUpdateRequired(prompt, ccTask, () -> {
                String outputFile = dpFilesService.getOutputFilePath(ccTask);
                log.info("Creating content of " + outputFile);
                String content = llmService.chat(ccTask, prompt);
                if( StringUtils.hasText(dpcc.getFilterList()) ) {
                    content = applyContentFilters(content, dpcc.getFilterList());
                }
                if (StringUtils.hasText(dpcc.getPs())) {
                    content = content + ccTask.getDpContentCreation().getPs();
                }
                try (OutputSink sink = outputSinkFactory.createSink(Path.of(outputFile))) {
                    sink.println(content);
                } catch (IOException e) {
                    logService.logMessage(
                        new DPLogMessage(Level.SEVERE, "Error while trying to create content for " + ccTask.getDpJob().getRootDir().getAbsolutePath() + ": " + e.getMessage())
                    );
                }
            });
        } catch (TemplateException | IOException e) {
            logService.logMessage(
                    new DPLogMessage(Level.SEVERE, "Error while trying to create prompt for " + ccTask.getDpJob().getRootDir().getAbsolutePath() + ": " + e.getMessage())
            );
        }
    }

    private String applyContentFilters(String content, String filterList) {
        for(String filterName : filterList.split(",")) {
            ContentFilter filter = contentFilterList.stream()
                .filter(f -> f.name().equals(filterName))
                .findFirst()
                .orElse(null);
            if( filter != null ) {
                content = filter.filter(content);
            }
        }
        return content;
    }
}
