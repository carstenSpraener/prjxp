package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;

import java.io.File;
import java.util.List;

/**
 * Interface for resolving dynamic content within prompt templates.
 * <p>
 * Implementations of this interface are used as Handlebars helpers to inject project-specific
 * data into the prompts before they are sent to the LLM.
 * </p>
 */
public interface TemplateResolver {
    /**
     * Returns the unique identifier for this resolver, used as the helper name in Handlebars templates.
     *
     * @return the resolver ID
     */
    String getID();

    default List<String> getAliases() {
        return List.of();
    }

    /**
     * Resolves a value based on the provided context and options.
     *
     * @param baseDir the configuration directory used as a base for resolution
     * @param context the current context of the template execution
     * @param options Handlebars options for the helper call
     * @return the resolved string to be inserted into the template
     * @throws Exception if an error occurs during resolution
     */
    String resolve(File baseDir, Object context, Options options) throws Exception;
}
