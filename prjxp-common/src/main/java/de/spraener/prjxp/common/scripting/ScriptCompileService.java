package de.spraener.prjxp.common.scripting;
import org.springframework.stereotype.Component;

import javax.script.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

@Component
public class ScriptCompileService {
    private ScriptEngineManager manager = new ScriptEngineManager();

    public CompiledScript compile(Path scriptFile, ScriptEngine engine) throws Exception {
        String scriptContent = Files.readString(scriptFile);
        return compile(scriptContent, engine);
    }

    @SafeVarargs
    public final CompiledScript compile(String scriptContent, ScriptEngine engine, Consumer<ScriptEngine>... modifiers) throws Exception {
        CompiledScript compiledScript = ((Compilable) engine).compile(scriptContent);
        if( modifiers!=null ) {
            for( var m : modifiers ) {
                m.accept(engine);
            }
        }
        return compiledScript;
    }

    public ScriptEngine createEngine(String name) {
        return manager.getEngineByName(name);
    }
}