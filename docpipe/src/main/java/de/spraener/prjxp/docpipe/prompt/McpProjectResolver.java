package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * Handlebars helper that injects project context for MCP tool usage.
 * <p>
 * Usage in templates:
 * <pre>
 *   {{mcp-project project="prjxp"}}
 *   {{mcpProject project="my-project"}}
 * </pre>
 */
@Component
@Log
public class McpProjectResolver implements TemplateResolver {

    @Override
    public String getID() {
        return "mcp-project";
    }

    @Override
    public List<String> getAliases() {
        return List.of("mcpProject");
    }

    @Override
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        // Named parameter: project="..." (default to empty string if not provided)
        String project = options.hash("project", "");

        if (project == null || project.isBlank()) {
            return "Search the embedded project using available MCP tools (vectorSearch, grep, readFile).";
        }

        return "Search the embedded project '" + project + "' using available MCP tools (vectorSearch, grep, readFile, readBySignature). "
                + "Use these tools to gather information about the project's structure, modules, and key classes before answering.";
    }
}
