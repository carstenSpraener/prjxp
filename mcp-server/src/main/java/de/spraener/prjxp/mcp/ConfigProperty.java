package de.spraener.prjxp.mcp;

/** One configuration property of a project, as listed by the {@code listConfig} tool. */
public record ConfigProperty(String name, String type, String defaultValue,
                             String description, String source) {
}
