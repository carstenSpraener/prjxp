package de.spraener.prjxp.common.config;

import lombok.Data;
import java.util.List;

@Data
public class McpServerReference {
    private String name;
    private String type; // "stdio", "http" or "groovy" (NEU)
    private String command;
    private List<String> args;
    private String url;
    // NEW: default project name for MCP tool queries
    private String defaultProject;

    /** Directory containing .groovy tool definition files. Default: "./groovyTools" (relative to project root). */
    private String scriptDir;
}
