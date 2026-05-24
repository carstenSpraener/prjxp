package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * Resolver for the "gr" prompt template helper.
 * <p>
 * This resolver uses {@link GRPromptEnrichment} to enrich the prompt content 
 * based on a project identifier.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class GRResolver implements TemplateResolver {
    private final GRPromptEnrichment enrichment;

    /**
     * Returns the identifier for this resolver.
     *
     * @return the string "gr"
     */
    @Override
    public String getID() {
        return "gr";
    }

    /**
     * Resolves the prompt content by enriching it using GRPromptEnrichment.
     *
     * @param baseDir the base configuration directory
     * @param context the resolution context
     * @param options the Handlebars options, including the "prj" parameter for project identification
     * @return the enriched prompt content
     * @throws Exception if an error occurs during enrichment
     */
    @Override
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        String content = options.fn.text();
        String prj = options.hash("prj", "default");
        return enrichment.enrich(prj, content);
    }
}
