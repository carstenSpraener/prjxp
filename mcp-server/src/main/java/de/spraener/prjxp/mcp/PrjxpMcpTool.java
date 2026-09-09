package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.lang3.StringUtils;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Component
@RequiredArgsConstructor
@Log
public class PrjxpMcpTool {
    private final GRPromptEnrichment enrichment;
    private final PrjXPConfig cfg;

    private final GrepSearchService grepSearchService;

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

    @McpTool(name="grep", description = """
            SEARCH-TOOL: Exact full-text search over all chunks of a project.
            STRATEGIE: Use this to narrow down results after vectorSearch, e.g. for exact identifiers or strings.
            PARAMETER-RULE: Pass the exact string you are looking for as query.
            """)
    public List<SearchHit> grep(
        @McpToolParam(description = "Exact search string (required).", required = true)
        String query,

        @McpToolParam(description = "Optional project name. Defaults to the active project.", required = false)
        String project,

        @McpToolParam(description = "Optional language filter (e.g. 'java', 'ts').", required = false)
        String language,

        @McpToolParam(description = "Maximum number of results (default 10, max 100).", required = false)
        int limit
    ) {
        if( limit == 0 ) limit = SearchLimits.DEFAULT_LIMIT;
        if(StringUtils.isEmpty(project)) project = "default";

        return grepSearchService.search(query.trim(), project, language, limit);
    }
}
