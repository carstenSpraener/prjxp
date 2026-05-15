package de.spraener.prjxp.docpipe.content;

import de.spraener.prjxp.docpipe.DocPipeConfig;
import de.spraener.prjxp.docpipe.llm.LLMService;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.prompt.PromptResolvingService;
import de.spraener.prjxp.docpipe.prompt.TemplateException;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

@Service
@RequiredArgsConstructor
@Log
public class ContentCreationService {
    private final PromptResolvingService promptResolvingService;
    private final LLMService llmService;

    public void createContent(ContentCreationTask ccTask) {
        try {
            DPContentCreation dpcc = ccTask.getDpContentCreation();
            File ccDir = ccTask.getDpJob().getRootDir();

            String prompt = promptResolvingService.resolve(ccTask);
            log.info("Creating content of "+dpcc.getOutputFile());
            String content = llmService.chat(ccTask, prompt);
            if(StringUtils.hasText(dpcc.getPs()) ) {
                content = content+ccTask.getDpContentCreation().getPs();
            }
            String outputFile = ccDir.getAbsoluteFile()+"/"+ccTask.getDpContentCreation().getOutputFile();
            try (OutputStream os = Files.newOutputStream(Path.of(outputFile))) {
                IOUtils.write(content, os, StandardCharsets.UTF_8);
            }
        } catch( IOException | TemplateException e) {
            log.log(Level.SEVERE, "Error while trying to create content for "+ccTask.getDpJob().getRootDir().getAbsolutePath()+": "+e.getMessage(), e);
        }
    }
}
