package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;
import de.spraener.prjxp.common.scripting.ScriptCompileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import javax.script.Bindings;
import java.io.File;
import java.util.Map;

@Component
@Log
@RequiredArgsConstructor
/**
 * A template resolver that executes Groovy scripts to dynamically generate prompt content.
 * <p>
 * This resolver allows for complex logic within the prompt templates by executing 
 * Groovy scripts. It provides access to the configuration directory, Handlebars options, 
 * and the Spring ApplicationContext within the script's bindings.
 * </p>
 */
public class GroovyResolver implements TemplateResolver {
    private final ScriptCompileService scriptCompileService;
    private final ApplicationContext applicationContext;

    @Override
    /**
     * Returns the identifier for this resolver.
     * 
     * @return "groovy"
     */
    public String getID() {
        return "groovy";
    }

    @Override
    /**
     * Resolves the prompt content by executing a Groovy script.
     * <p>
     * The method extracts the script content from the Handlebars template, compiles it 
     * using the {@link ScriptCompileService}, and evaluates it. The script has access to 
     * several bindings, including the base directory and the application context.
     * </p>
     *
     * @param baseDir the configuration directory used as a base for resolution
     * @param context the current context of the template execution
     * @param options Handlebars options, where {@code options.fn.text()} provides the script content
     * @return the result of the Groovy script execution as a string, or an empty string if the result is null
     * @throws Exception if an error occurs during script compilation or execution
     */
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        String content = options.fn.text();
        Object result = scriptCompileService.compile(content, scriptCompileService.createEngine("groovy"),
            e -> {
                Bindings b = e.getBindings(javax.script.ScriptContext.ENGINE_SCOPE);
                b.put("dir", baseDir);
                b.put("options", options);
                b.put("applicationContext", applicationContext);
                b.put("dpContext", context);
                b.put("currentFile", ((Map<String,String>)context).get("currentFile"));
            }
        ).eval();
        if( result==null ) {
            return "";
        } else {
            return result.toString();
        }
    }
}
