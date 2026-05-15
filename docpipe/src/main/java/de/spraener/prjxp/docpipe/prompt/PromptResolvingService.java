package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Options;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PromptResolvingService {
    private final List<TemplateResolver> templateResolvers;

    public String resolve(String promptTemplateContent, File baseDir) throws IOException {
        Handlebars handlebars = new Handlebars();
        handlebars.setStringParams(true);

        for( var tr :  templateResolvers ) {
            TRHelper trh = new TRHelper(baseDir, tr);
            handlebars.registerHelper(tr.getID(),trh);
        }
        var template = handlebars.compileInline(promptTemplateContent);
        return template.apply(new Object());
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
                return templateResolver.resolve(baseDir,options);
            } catch( Exception e) {
                log.severe("Could not resolve "+templateResolver.getID()+" in directory "+baseDir+": "+e.getMessage());
                return "ERROR IN RESOLVING: "+e.getMessage();
            }
        }
    }
}
