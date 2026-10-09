package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;
import de.spraener.prjxp.common.toolregistry.ToolRegistry;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
@Log
public class McpProjectResolver implements TemplateResolver {

    private final ToolRegistry toolRegistry;

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

        // Get dynamic tool descriptions from ToolRegistry
        List<String> toolDescriptions = toolRegistry.getToolDescriptions();

        if (toolDescriptions.isEmpty()) {
            return "";   // No tools available — inject nothing
        }

        StringBuilder sb = new StringBuilder();
        sb.append("You have access to the following tools:\n\n");
        for (String desc : toolDescriptions) {
            sb.append(desc).append("\n\n");
        }

        if (project != null && !project.isBlank()) {
            sb.append("The active project is: '").append(project).append("'.\n\n");
            sb.append("Use these tools to gather information about the project's structure, modules, and key classes before answering.");
        } else {
            sb.append("Use these tools to gather information before answering the user's question.");
        }

        return sb.toString();
    }
}
