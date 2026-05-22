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
public class GroovyResolver implements TemplateResolver {
    private final ScriptCompileService scriptCompileService;
    private final ApplicationContext applicationContext;

    @Override
    public String getID() {
        return "groovy";
    }

    @Override
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        String content = options.fn.text();
        Object result = scriptCompileService.compile(content, scriptCompileService.createEngine("groovy"),
            e -> {
                Bindings b = e.getBindings(javax.script.ScriptContext.ENGINE_SCOPE);
                b.put("dir", baseDir);
                b.put("options", options);
                b.put("applicationContext", applicationContext);
            }
        ).eval();
        if( result==null ) {
            return "";
        } else {
            return result.toString();
        }
    }
}
