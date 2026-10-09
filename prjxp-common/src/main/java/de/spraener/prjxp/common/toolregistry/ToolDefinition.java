package de.spraener.prjxp.common.toolregistry;

import java.util.Map;
import java.util.function.Function;
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
    Function<Map<String, Object>, String> execute;

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
