package de.spraener.prjxp.common.toolregistry;

import java.util.Map;
import java.util.function.Function;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.Value;

/**
 * Immutable definition of a single tool: name, description, parameters and an execute function.
 * <p>
 * A tool may carry an explicit {@link ToolSpecification} (full JSON schema incl. types and required
 * flags). If none is supplied, a schema is derived from {@link #parameters} where every parameter is
 * treated as an optional string — this keeps simple tools (e.g. Groovy scripts) free of boilerplate.
 */
@Value
public class ToolDefinition {
    String name;
    String description;
    Map<String, String> parameters;   // paramName -> paramDescription

    /** Executes the tool with the given parameters and returns a text result. */
    Function<Map<String, Object>, String> execute;

    /** Optional explicit schema; when null a string-only schema is derived from {@link #parameters}. */
    ToolSpecification specification;

    public ToolDefinition(String name,
                          String description,
                          Map<String, String> parameters,
                          Function<Map<String, Object>, String> execute) {
        this(name, description, parameters, execute, null);
    }

    public ToolDefinition(String name,
                          String description,
                          Map<String, String> parameters,
                          Function<Map<String, Object>, String> execute,
                          ToolSpecification specification) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
        this.execute = execute;
        this.specification = specification;
    }

    /**
     * Returns the tool schema for native function calling: the explicit specification when present,
     * otherwise a generated one where all parameters are optional strings.
     */
    public ToolSpecification toSpecification() {
        if (specification != null) {
            return specification;
        }
        JsonObjectSchema.Builder schema = JsonObjectSchema.builder();
        if (parameters != null) {
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                schema.addStringProperty(entry.getKey(), entry.getValue());
            }
        }
        return ToolSpecification.builder()
                .name(name)
                .description(description != null ? description : "")
                .parameters(schema.build())
                .build();
    }

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
