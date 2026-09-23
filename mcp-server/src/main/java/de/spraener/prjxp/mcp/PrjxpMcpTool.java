package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.lang3.StringUtils;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

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
            1. ALWAYS call this tool FIRST for discovery: finding classes, understanding structure, locating implementations, exploring the codebase.
            2. For FULL METHOD BODIES: follow up with grep using the exact method signature (e.g. "public MClass createMClass").
               Vector search returns skeletons for non-hit methods by design — grep is the way to retrieve complete implementations.
            3. You can execute multiple follow-up queries with refined search terms to dig deeper.
            4. REWRITE the query parameter: Convert the context of the conversation into a targeted, standalone search query optimized for semantic vector retrieval.

            RESULT:
            The search returns method implementations only if the vector search hits a method chunk AND skeletonsOnly is set to false.
            Otherwise it returns simple class skeletons with imports and project dependencies for an architectural overview.
            Use grep on the exact signature to get full bodies of methods you need implementation details for.
            """)
    public String vectorSearch(
            @McpToolParam(description = "A targeted, standalone search prompt optimized for vector retrieval based on what you need to find.", required = true)
            String userQuestion,

            @McpToolParam(description = "Optional project name to narrow the scope. Leave empty/default if unknown.", required = false)
            String projectName,

            @McpToolParam(description="""
                Similarity in vector space. Range: 0.0 - 1.0 Higher values yield more precise results; Default is 0.85.
                Range: 1.0 to 0.9 = very precise, 0.9 to 0.8 = precise, 0.8 to 0.7 = balanced, 0.7 and lower = fantasy land.
                Illegal values are clamped to 0.85.
                """, required = false)
            Double similarity,
            @McpToolParam(description="""
                Maximum number of results to return. Default is 20.
                """, required = false)
            Integer maxResults,
            @McpToolParam(description="""
                Do you need full method context on hit methods or do you always want to see class skeletons only? Default is false (full context).
                """, required = false)
            Boolean skeletonsOnly
    ) {

        String prefix = """
                """;
        if (projectName == null || projectName.isEmpty() || "default".equals(projectName)) {
            projectName = cfg.getActiveProject().get().getName();
        }
        if( similarity == null || similarity < 0.0 || similarity > 1.0 ) {
            similarity = 0.85;
        }
        if( maxResults==null ||maxResults>20 || maxResults<1 ) {
            maxResults = 20;
        }
        if( skeletonsOnly==null) {
            skeletonsOnly = Boolean.FALSE;
        }
        log.info(String.format("searching context for '%s' for project '%s'.", userQuestion, projectName));
        String context = enrichment.enrich(projectName, userQuestion, similarity, maxResults, skeletonsOnly);

        String result = String.format("%s\n%s", prefix, context);
        log.info(String.format("    responding with %d chars (about %d tokens) of content", result.length(), result.length() / 4));
        return result;
    }

    @McpTool(name="grep", description = """
            SEARCH-TOOL: Exact full-text search over all chunks of a project.
            STRATEGIE: Two-step workflow — vectorSearch for discovery, then grep
            for the exact method signature to retrieve its FULL BODY. Chunks that
            contain a hit include the complete method body, not just the skeleton.
            PARAMETER-RULE: Pass the exact string (e.g., "public MClass createMClass")
            as query. Use this when you need implementation details, not just structure.
            """)
    public List<SearchHit> grep(
        @McpToolParam(description = "Exact search string (required).", required = true)
        String query,

        @McpToolParam(description = "Optional project name. Defaults to the active project.", required = false)
        String project,

        @McpToolParam(description = "Optional language filter (e.g. 'java', 'ts').", required = false)
        String language,

        @McpToolParam(description = "Maximum number of results (default 10, max 100).", required = false)
        Integer limit
    ) {
        if( limit == null ) {
            limit = 0;
        }
        if( limit == 0 ) limit = SearchLimits.DEFAULT_LIMIT;
        if(StringUtils.isEmpty(project)) project = "default";

        return grepSearchService.search(query.trim(), project, language, limit);
    }

}
