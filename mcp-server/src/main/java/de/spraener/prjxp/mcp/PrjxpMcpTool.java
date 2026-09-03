package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Log
public class PrjxpMcpTool {

    private final GRPromptEnrichment enrichment;
    private final PrjXPConfig cfg;

    @McpTool(name = "vectorSearch", description = """
            CRITICAL PRIMARY SEARCH TOOL: Delivers relevant semantic information from the project context.

            USAGE RULES:
            1. ALWAYS call this tool BEFORE attempting any file system searches, grep, or terminal commands.
            2. Do NOT use grep or file navigation UNLESS this tool returns no useful results (fallback only).
            3. You can execute multiple follow-up queries with refined search terms to dig deeper.
            4. REWRITE the query parameter: Convert the context of the conversation into a targeted, standalone search query optimized for semantic vector retrieval.
            """)
    public String vectorSearch(
            @McpToolParam(description = "A targeted, standalone search prompt optimized for vector retrieval based on what you need to find.", required = true)
            String userQuestion,

            @McpToolParam(description = "Optional project name to narrow the scope. Leave empty/default if unknown.", required = false)
            String projectName) {

        String prefix = """
                """;
        if (projectName == null || projectName.isEmpty() || "default".equals(projectName)) {
            projectName = cfg.getActiveProject().get().getName();
        }

        log.info(String.format("searching context for '%s' for project '%s'.", userQuestion, projectName));
        String context = enrichment.enrich(projectName, userQuestion);
        String result = String.format("%s\n%s", prefix, context);
        log.info(String.format("    responding with %d chars (about %d tokens) of content", result.length(), result.length() / 4));
        return result;
    }
}
