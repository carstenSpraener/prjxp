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
/**
 * Service for resolving prompt templates into final prompts.
 * <p>
 * This service uses the Handlebars templating engine to process prompt templates. It integrates
 * various {@link TemplateResolver} implementations as Handlebars helpers, allowing dynamic data
 * to be injected into the prompts based on the project context.
 * </p>
 */
public class PromptResolvingService {
    private final List<TemplateResolver> templateResolvers;
    private final DotDPFilesService dpFilesService;

    /**
     * Resolves the prompt for a given content creation task.
     * <p>
     * This method reads the prompt template file specified in the task configuration and
     * resolves it using the project's configuration directory.
     * </p>
     *
     * @param ccTask the task containing the prompt template reference
     * @return the fully resolved prompt string
     * @throws IOException if an error occurs while reading the template file
     */
    public String resolve(ContentCreationTask ccTask) throws IOException {
        File cfgDir = dpFilesService.dotPipeDir(ccTask);
        DPContentCreation dpcc = ccTask.getDpContentCreation();

        String promptTemplate = readTemplate(cfgDir, dpcc.getPrompt());
        String prompt = resolve(dpcc, promptTemplate, cfgDir);
        log.finer(()-> "Prompt for creation of File "+dpcc.getOutputFile()+" is: " + prompt);
        return prompt;
    }

    /**
     * Resolves a prompt template string using Handlebars and registered resolvers.
     * <p>
     * This method initializes a Handlebars instance, registers all available {@link TemplateResolver}s
     * as helpers, and applies the template to generate the final prompt.
     * </p>
     *
     * @param promptTemplate the raw template string to resolve
     * @param cfgDir the configuration directory used as a base for resolvers
     * @return the resolved prompt string
     * @throws IOException if an error occurs during template resolution
     */
    public String resolve(DPContentCreation dpcc, String promptTemplate, File cfgDir) throws IOException {
        Handlebars handlebars = new Handlebars();
        handlebars.setStringParams(true);

        for( var tr :  templateResolvers ) {
            TRHelper trh = new TRHelper(cfgDir, tr);
            handlebars.registerHelper(tr.getID(),trh);
        }
        var template = handlebars.compileInline(promptTemplate);
        String prompt = template.apply(dpcc.getArgs());
        return prompt;
    }

    /**
     * Reads the prompt template from a file in the configuration directory.
     *
     * @param cfgDir the configuration directory where the template is located
     * @param prompt the name of the prompt template file
     * @return the content of the template file as a string
     * @throws IOException if an error occurs while reading the file
     */
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
