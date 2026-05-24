package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
@RequiredArgsConstructor
public class GRResolver implements TemplateResolver {
    private final GRPromptEnrichment enrichment;

    @Override
    public String getID() {
        return "gr";
    }

    @Override
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        String content = options.fn.text();
        String prj = options.hash("prj", "default");
        return enrichment.enrich(prj, content);
    }
}
