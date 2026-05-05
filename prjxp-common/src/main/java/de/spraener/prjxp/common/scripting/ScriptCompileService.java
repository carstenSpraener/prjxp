package de.spraener.prjxp.common.scripting;
import org.springframework.stereotype.Component;

import javax.script.*;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class ScriptCompileService {
    private ScriptEngineManager manager = new ScriptEngineManager();

    public CompiledScript compile(Path scriptFile, ScriptEngine engine) throws Exception {
        String scriptContent = Files.readString(scriptFile);
        CompiledScript compiledScript = ((Compilable) engine).compile(scriptContent);
        return compiledScript;
    }

    public ScriptEngine createEngine(String name) {
        return manager.getEngineByName(name);
    }
}