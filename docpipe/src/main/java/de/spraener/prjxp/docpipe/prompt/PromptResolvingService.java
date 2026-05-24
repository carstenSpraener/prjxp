package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Options;
import de.spraener.prjxp.docpipe.config.DotDPFilesService;
import de.spraener.prjxp.docpipe.content.ContentCreationTask;
import de.spraener.prjxp.docpipe.model.DPContentCreation;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
@Log
public class PromptResolvingService {
    private final List<TemplateResolver> templateResolvers;
    private final DotDPFilesService dpFilesService;

    public String resolve(ContentCreationTask ccTask) throws IOException {
        File cfgDir = dpFilesService.dotPipeDir(ccTask);
        DPContentCreation dpcc = ccTask.getDpContentCreation();

        String promptTemplate = readTemplate(cfgDir, dpcc.getPrompt());
        String prompt = resolve(promptTemplate, cfgDir);
        log.finer(()-> "Prompt for creation of File "+dpcc.getOutputFile()+" is: " + prompt);
        return prompt;
    }

    public String resolve(String promptTemplate, File cfgDir) throws IOException {
        Handlebars handlebars = new Handlebars();
        handlebars.setStringParams(true);

        for( var tr :  templateResolvers ) {
            TRHelper trh = new TRHelper(cfgDir, tr);
            handlebars.registerHelper(tr.getID(),trh);
        }
        var template = handlebars.compileInline(promptTemplate);
        String prompt = template.apply(new Object());
        return prompt;
    }

    private String readTemplate(File cfgDir, String prompt) throws IOException {
        return IOUtils.toString(new FileReader(cfgDir.getAbsolutePath()+"/"+prompt));
    }


    @Data
    @RequiredArgsConstructor
    @Log
    private static class TRHelper implements Helper<Object> {
        private final File baseDir;
        private final TemplateResolver templateResolver;

        @Override
        public Object apply(Object context, Options options) throws IOException {
            try {
                return templateResolver.resolve(baseDir, context, options);
            } catch( Exception e) {
                throw new TemplateException(e);
            }
        }
    }
}
