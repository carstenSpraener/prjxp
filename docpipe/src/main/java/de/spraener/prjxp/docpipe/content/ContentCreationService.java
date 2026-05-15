package de.spraener.prjxp.docpipe.content;

import de.spraener.prjxp.docpipe.DocPipeConfig;
import de.spraener.prjxp.docpipe.llm.LLMService;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import de.spraener.prjxp.docpipe.prompt.PromptResolvingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
@RequiredArgsConstructor
@Log
public class ContentCreationService {
    private final PromptResolvingService promptResolvingService;
    private final LLMService llmService;

    public void createContent(ContentCreation cc) {
        try {
            DPContentCreation dbCC = cc.getDpContentCreation();
            File ccDir = cc.getDpJob().getRootDir();
            File cfgDir = new File(cc.getDpJob().getRootDir().getAbsolutePath() +"/" + DocPipeConfig.DP_DIR);

            String promptTemplate = readTemplate(cfgDir, dbCC.getPrompt());
            String prompt = promptResolvingService.resolve(
                    promptTemplate,
                    cfgDir
            );
            log.finer(()-> "Prompt for creation of File "+dbCC.getOutputFile()+" is: " + prompt);
            log.info("Creating content of "+dbCC.getOutputFile());
            String content = llmService.chat(cc.getDpJob(), cc.getDpContentCreation(), prompt);
            if(StringUtils.hasText(cc.getDpContentCreation().getPs()) ) {
                content = content+cc.getDpContentCreation().getPs();
            }
            String outputFile = ccDir.getAbsoluteFile()+"/"+cc.getDpContentCreation().getOutputFile();
            IOUtils.write(content, Files.newOutputStream(Path.of(outputFile)));
        } catch( Exception e) {
            log.severe("Error while trying to create content for "+cc.getDpJob().getRootDir().getAbsolutePath()+": "+e.getMessage());
        }
    }

    private String readTemplate(File cfgDir, String prompt) throws IOException {
        return IOUtils.toString(new FileReader(cfgDir.getAbsolutePath()+"/"+prompt));
    }
}
