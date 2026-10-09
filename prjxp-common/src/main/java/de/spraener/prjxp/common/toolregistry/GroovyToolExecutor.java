package de.spraener.prjxp.common.toolregistry;

import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.GroovyShell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Compiles and executes Groovy script files as tools. Each .groovy file must define:
 * - name (String), description (String), parameters (Map<String, String>)
 * - execute(params) closure that returns a String result
 * The script must return: [name: ..., description: ..., parameters: ..., execute: closure]
 */
@Component
public class GroovyToolExecutor {

    private final Logger log = LoggerFactory.getLogger(GroovyToolExecutor.class);

    /**
     * Load all .groovy files from scriptDir, compile them and register via the consumer.
     * Each file defines exactly one tool.
     */
    public void loadAndRegister(Path scriptDir, Consumer<ToolDefinition> register) {
        if (!Files.exists(scriptDir)) {
            log.warn("Groovy script directory does not exist: " + scriptDir);
            return;
        }

        try (var stream = Files.list(scriptDir)) {
            List<Path> groovyFiles = stream
                    .filter(p -> p.toString().endsWith(".groovy"))
                    .sorted()
                    .toList();

            for (Path file : groovyFiles) {
                try {
                    ToolDefinition tool = compileTool(file);
                    if (tool != null) {
                        register.accept(tool);
                        log.info("Registered Groovy tool '{}' from {}", tool.getName(), file.getFileName());
                    }
                } catch (Exception e) {
                    log.error("Failed to compile Groovy tool from {}: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Failed to list Groovy script directory {}: {}", scriptDir, e.getMessage());
        }
    }

    /** Compile a single .groovy file into a ToolDefinition. */
    private ToolDefinition compileTool(Path scriptFile) throws IOException {
        // Read the script content from file
        String source = new String(Files.readAllBytes(scriptFile), StandardCharsets.UTF_8);

        // Create binding with sandboxed context variables
        Binding binding = new Binding();
        binding.setVariable("projectRoot", Path.of(System.getProperty("user.dir")));
        binding.setVariable("baseDir", Path.of(System.getProperty("user.dir")));
        binding.setVariable("log", log);

        GroovyShell shell = new GroovyShell(binding);
        Object result = shell.evaluate(source);

        if (!(result instanceof Map<?, ?> map)) {
            log.warn("Groovy script {} did not return a Map. Expected [name, description, parameters, execute]",
                    scriptFile.getFileName());
            return null;
        }

        String name = map.get("name") != null ? String.valueOf(map.get("name")) : null;
        String description = map.get("description") != null ? String.valueOf(map.get("description")) : null;
        @SuppressWarnings("unchecked")
        Map<String, String> parameters = (Map<String, String>) map.get("parameters");

        if (name == null || name.equals("null") || description == null || description.equals("null")) {
            log.warn("Groovy script {} missing 'name' or 'description'", scriptFile.getFileName());
            return null;
        }

        // Get the execute closure from the script result
        Closure<?> executeClosure = (Closure<?>) map.get("execute");
        if (executeClosure == null) {
            log.warn("Groovy script {} missing 'execute' closure", scriptFile.getFileName());
            return null;
        }

        // Create a ToolDefinition that reuses the same binding (context is already set)
        return new ToolDefinition(name, description, parameters != null ? new HashMap<>(parameters) : Map.of(),
                params -> {
                    // The closure already has access to projectRoot, baseDir, log from the binding
                    Object resultObj = executeClosure.call(params);
                    return resultObj != null ? String.valueOf(resultObj) : "";
                });
    }
}
