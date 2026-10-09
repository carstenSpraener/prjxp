# Phase 01: Core Abstractions

> **STRICT EXECUTION RULES FOR THE AGENT:**
> 1. DO NOT run repository scans (`find_files`, `grep`, `search_code`, `list_dir`).
> 2. Read ONLY the files explicitly listed in the "File Scope" section below.
> 3. All required interfaces/DTOs are provided inline in this document. Do not fetch them from the codebase.
> 4. **MANDATORY FINAL STEP:** As soon as the implementation and tests (>= 80% coverage) are green, execute `/compact` IMMEDIATELY to clean up the context window before returning control.

## 1. Target & Scope
* **Objective:** Create the core abstractions for a unified tool registry: `ToolRegistry`, `ToolDefinition`, and `GroovyToolExecutor`.
* **Target Files to Create/Modify:**
    * Create: `prjxp-common/src/main/java/de/spraener/prjxp/common/toolregistry/ToolRegistry.java`
    * Create: `prjxp-common/src/main/java/de/spraener/prjxp/common/toolregistry/ToolDefinition.java`
    * Create: `prjxp-common/src/main/java/de/spraener/prjxp/common/toolregistry/GroovyToolExecutor.java`
    * Create: `prjxp-common/src/test/java/de/spraener/prjxp/common/toolregistry/ToolRegistryTest.java`
    * Create: `prjxp-common/src/test/java/de/spraener/prjxp/common/toolregistry/GroovyToolExecutorTest.java`

## 2. Inline Required Context (Contracts & Signatures)

### `ToolDefinition.java`
```java
package de.spraener.prjxp.common.toolregistry;

import java.util.Map;
import lombok.Value;

/**
 * Immutable definition of a single tool: name, description, parameters and an execute function.
 */
@Value
public class ToolDefinition {
    String name;
    String description;
    Map<String, String> parameters;   // paramName -> paramDescription

    /** Executes the tool with the given parameters and returns a text result. */
    String execute(Map<String, Object> params);

    /** Returns a human-readable description for prompt injection. */
    public String toDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("(\n");
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            sb.append("    ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
        }
        sb.append(") - ").append(description);
        return sb.toString();
    }
}
```

### `ToolRegistry.java`
```java
package de.spraener.prjxp.common.toolregistry;

import dev.langchain4j.mcp.client.McpClient;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Central registry for all tools (MCP clients + Groovy script tools).
 * Single source of truth: provides tool descriptions for prompt injection and executes tools on demand.
 */
@Component
@Log
public class ToolRegistry {

    private final List<ToolDefinition> tools = new ArrayList<>();
    private final GroovyToolExecutor groovyToolExecutor;

    public ToolRegistry(GroovyToolExecutor groovyToolExecutor) {
        this.groovyToolExecutor = groovyToolExecutor;
    }

    /** Register a ToolDefinition directly. */
    public void register(ToolDefinition tool) {
        tools.add(tool);
    }

    /** Register an MCP client — extracts its tool definitions and wraps them. */
    public void registerMcpClient(McpClient client) {
        // MCP clients expose tools via their listTools() method.
        // Wrap each tool in a ToolDefinition that delegates execution to the MCP client.
        // Implementation: iterate client.listTools(), create ToolDefinition per tool, register them.
    }

    /** Load Groovy script tools from a directory. Each .groovy file defines one tool. */
    public void loadGroovyTools(Path scriptDir) {
        groovyToolExecutor.loadAndRegister(scriptDir, this::register);
    }

    /** Get all tool descriptions for prompt injection. */
    public List<String> getToolDescriptions() {
        return tools.stream().map(ToolDefinition::toDescription).toList();
    }

    /** Execute a tool by name with the given parameters. */
    public String execute(String toolName, Map<String, Object> params) {
        return tools.stream()
                .filter(t -> t.getName().equals(toolName))
                .findFirst()
                .map(t -> {
                    try {
                        return t.execute(params);
                    } catch (Exception e) {
                        log.severe("Tool execution failed for " + toolName + ": " + e.getMessage());
                        return "Error executing tool '" + toolName + "': " + e.getMessage();
                    }
                })
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + toolName));
    }

    /** Get all registered tools (for testing). */
    public List<ToolDefinition> getTools() {
        return new ArrayList<>(tools);
    }
}
```

### `GroovyToolExecutor.java`
```java
package de.spraener.prjxp.common.toolregistry;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
    private ToolDefinition compileTool(Path scriptFile) {
        GroovyShell shell = new GroovyShell();

        // Bind sandboxed context variables
        Binding binding = new Binding();
        binding.setVariable("projectRoot", Path.of(System.getProperty("user.dir")));
        binding.setVariable("baseDir", Path.of(System.getProperty("user.dir")));
        // configSubset and log are injected at runtime, not compile time

        Object result = shell.evaluate(scriptFile.toFile(), binding);
        if (!(result instanceof Map<?, ?> map)) {
            log.warn("Groovy script {} did not return a Map. Expected [name, description, parameters, execute]",
                    scriptFile.getFileName());
            return null;
        }

        String name = String.valueOf(map.get("name"));
        String description = String.valueOf(map.get("description"));
        @SuppressWarnings("unchecked")
        Map<String, String> parameters = (Map<String, String>) map.get("parameters");

        if (name == null || description == null) {
            log.warn("Groovy script {} missing 'name' or 'description'", scriptFile.getFileName());
            return null;
        }

        // Wrap the execute closure in a ToolDefinition
        groovy.lang.Closure<?> executeClosure = (groovy.lang.Closure<?>) map.get("execute");
        if (executeClosure == null) {
            log.warn("Groovy script {} missing 'execute' closure", scriptFile.getFileName());
            return null;
        }

        // Create a ToolDefinition that injects context at execution time
        return new ToolDefinition(name, description, parameters != null ? new HashMap<>(parameters) : Map.of(),
                params -> {
                    // Inject sandboxed context into the closure's binding before execution
                    Binding execBinding = executeClosure.getBinding();
                    execBinding.setVariable("projectRoot", Path.of(System.getProperty("user.dir")));
                    execBinding.setVariable("baseDir", Path.of(System.getProperty("user.dir")));
                    execBinding.setVariable("log", log);
                    // configSubset would be injected here (see Phase 02)

                    Object resultObj = executeClosure.call(params);
                    return resultObj != null ? String.valueOf(resultObj) : "";
                });
    }
}
```

## 3. Test Requirements (TDD)

### `ToolRegistryTest.java`
- **register()**: Register a ToolDefinition, verify it appears in `getTools()` and `getToolDescriptions()`
- **execute()**: Execute a registered tool with params, verify result
- **execute unknown tool**: Verify `IllegalArgumentException` is thrown
- **toDescription()**: Verify the description format includes name, params and description text

### `GroovyToolExecutorTest.java`
- **loadAndRegister valid tool**: Create a temp `.groovy` file with valid structure, verify it's compiled and registered
- **loadAndRegister missing directory**: Verify no crash, just a warning log
- **loadAndRegister invalid script**: Create a `.groovy` file without `name`/`description`, verify it's skipped with warning
- **execute closure receives params**: Verify the execute closure receives the params map and can use it

## 4. Dependencies
* `groovy-all` (already in classpath via existing GroovyResolver)
* `lombok` (for `@Value`)
* `slf4j-api` (for logging)
