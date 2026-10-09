package de.spraener.prjxp.common.toolregistry;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
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
        try {
            List<ToolSpecification> mcpTools = client.listTools();
            for (ToolSpecification spec : mcpTools) {
                ToolDefinition wrapped = new ToolDefinition(
                        spec.name(),
                        spec.description() != null ? spec.description() : "",
                        Map.of(), // MCP tool parameters handled by the client itself
                        params -> {
                            ToolExecutionRequest request = ToolExecutionRequest.builder()
                                    .name(spec.name())
                                    .arguments(toJson(params))
                                    .build();
                            ToolExecutionResult result = client.executeTool(request);
                            return result != null ? String.valueOf(result) : "";
                        }
                );
                register(wrapped);
            }
        } catch (Exception e) {
            log.severe("Failed to list tools from MCP client: " + e.getMessage());
        }
    }

    /** Convert a Map to a simple JSON-like string for MCP tool arguments. */
    private String toJson(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "{}";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(entry.getKey()).append("\":\"")
              .append(String.valueOf(entry.getValue()).replace("\"", "\\\""))
              .append("\"");
        }
        sb.append("}");
        return sb.toString();
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
                        return t.getExecute().apply(params);
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
