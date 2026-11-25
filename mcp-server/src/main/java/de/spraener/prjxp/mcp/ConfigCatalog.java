package de.spraener.prjxp.mcp;

import java.util.List;

/** Result of the {@code listConfig} tool: the project's configuration properties. */
public record ConfigCatalog(String project, List<ConfigProperty> properties, String error) {

    public static ConfigCatalog unavailable(String message) {
        return new ConfigCatalog(null, List.of(), message);
    }
}
