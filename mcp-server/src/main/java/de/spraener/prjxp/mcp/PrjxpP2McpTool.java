package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.FileView;
import de.spraener.prjxp.common.model.SymbolReadResult;
import lombok.RequiredArgsConstructor;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP surface of the P2 contract ({@code prjxp.hub.tool-profile=p2}).
 * <p>
 * Differences to P1, all of them tool-contract properties rather than question-specific tuning:
 * <ul>
 *   <li>the project is bound server-side ({@code prjxp.hub.bound-project}); no tool takes a
 *       {@code project} parameter and {@code listProjects} is gone,</li>
 *   <li>{@code grep} returns {@code file:line} hits instead of a bare file list,</li>
 *   <li>{@code vectorSearch} adds a snippet and a {@code readChunks} hint and de-duplicates ids,</li>
 *   <li>{@code readBySignature} appends method bodies to a class frame,</li>
 *   <li>only the nine tools that were actually used in both corpora are exposed.</li>
 * </ul>
 * The three rendering measures (grep lines, snippets, bodies) can be disabled one at a time via
 * {@link P2Measures}, so an A/B run can attribute an effect to a single measure.
 */
@Component
@ConditionalOnExpression("${prjxp.hub.enabled:false} && '${prjxp.hub.tool-profile:p1}'.toLowerCase() == 'p2'")
@RequiredArgsConstructor
public class PrjxpP2McpTool {

    private final PrjxpToolService toolService;
    private final HubToolProfile profile;
    private final P2Measures measures;

    @McpTool(name = "vectorSearch", description = PrjxpP2ToolDocs.VECTOR_SEARCH)
    public String vectorSearch(
            @McpToolParam(description = "Short English keyword query; identifiers verbatim.", required = true)
            String userQuestion,
            @McpToolParam(description = "Similarity floor, default 0.85.", required = false)
            Double similarity,
            @McpToolParam(description = "Max files, default 10 (max 20).", required = false)
            Integer maxResults,
            @McpToolParam(description = "code (default) | docs | all.", required = false)
            String sourceType,
            @McpToolParam(description = "Include test sources (default false).", required = false)
            Boolean includeTests,
            @McpToolParam(description = "Max output tokens, default 2000.", required = false)
            Integer tokenBudget) {
        return toolService.vectorSearchP2(userQuestion, profile.boundProject(), similarity, maxResults,
                sourceType, includeTests, tokenBudget, measures.snippets());
    }

    @McpTool(name = "grep", description = PrjxpP2ToolDocs.GREP)
    public String grep(
            @McpToolParam(description = "Text or pattern to find.", required = true)
            String query,
            @McpToolParam(description = "substring (default) | exact | regex.", required = false)
            String mode,
            @McpToolParam(description = "Optional language filter (e.g. 'java').", required = false)
            String language,
            @McpToolParam(description = "Max hits, default 20 (max 100).", required = false)
            Integer limit,
            @McpToolParam(description = "Include test sources (default true).", required = false)
            Boolean includeTests) {
        return toolService.grepP2(query, profile.boundProject(), mode, language, limit, includeTests,
                measures.grepLines());
    }

    @McpTool(name = "readFile", description = PrjxpP2ToolDocs.READ_FILE)
    public FileView readFile(
            @McpToolParam(description = "File path (absolute, relative, or name; lenient).", required = true)
            String file,
            @McpToolParam(description = "1-based start line.", required = false)
            Integer offset,
            @McpToolParam(description = "Max lines from offset (default 100).", required = false)
            Integer limit,
            @McpToolParam(description = "Whole file only for files ≤200 lines; capped.", required = false)
            Boolean full) {
        return toolService.readFile(file, profile.boundProject(), offset, limit, full);
    }

    @McpTool(name = "readBySignature", description = PrjxpP2ToolDocs.READ_BY_SIGNATURE)
    public SymbolReadResult readBySignature(
            @McpToolParam(description = "'FQN#name', class FQN, or bare method name.", required = true)
            String method,
            @McpToolParam(description = "Containing class FQN to disambiguate.", required = false)
            String container,
            @McpToolParam(description = "Class FQNs: append method bodies (default true).", required = false)
            Boolean includeBodies,
            @McpToolParam(description = "Max appended bodies, default 3 (max 10).", required = false)
            Integer maxBodies) {
        return toolService.readBySignatureP2(method, container, profile.boundProject(),
                includeBodies, maxBodies, measures.bodies());
    }

    @McpTool(name = "listFiles", description = PrjxpP2ToolDocs.LIST_FILES)
    public String listFiles(
            @McpToolParam(description = "Path prefix or glob ('*' spans '/'); empty = all.", required = false)
            String path,
            @McpToolParam(description = "Max files, default 50 (max 500).", required = false)
            Integer limit) {
        return toolService.listFiles(path, profile.boundProject(), limit);
    }

    @McpTool(name = "graphNode", description = PrjxpP2ToolDocs.GRAPH_NODE)
    public String graphNode(
            @McpToolParam(description = "Class FQN or method id; bare names match as suffix.", required = true)
            String symbol,
            @McpToolParam(description = "Max neighbours per direction, default 30.", required = false)
            Integer maxEdges,
            @McpToolParam(description = "Include JDK/library edges (default false).", required = false)
            Boolean includeExternal,
            @McpToolParam(description = "Skip this many per direction.", required = false)
            Integer offset,
            @McpToolParam(description = "FULL (default) | AGGREGATED.", required = false)
            String contentLevel,
            @McpToolParam(description = "Max output tokens, default 2000.", required = false)
            Integer tokenBudget) {
        return toolService.graphNode(symbol, profile.boundProject(), maxEdges, includeExternal, offset,
                contentLevel, tokenBudget);
    }

    @McpTool(name = "graphSearch", description = PrjxpP2ToolDocs.GRAPH_SEARCH)
    public String graphSearch(
            @McpToolParam(description = "Short English keyword query; identifiers verbatim.", required = true)
            String question,
            @McpToolParam(description = "bfs (default) | dfs.", required = false)
            String mode,
            @McpToolParam(description = "Traversal depth, 1-6 (default 3).", required = false)
            Integer depth,
            @McpToolParam(description = "Max output tokens, default 2000.", required = false)
            Integer tokenBudget,
            @McpToolParam(description = "Optional edge types: CALL, CONTAINS, FIELD, …", required = false)
            List<String> contextFilter,
            @McpToolParam(description = "Max seeds, 1-5 (default 3).", required = false)
            Integer maxSeeds) {
        return toolService.graphSearch(question, profile.boundProject(), mode, depth, tokenBudget,
                contextFilter, maxSeeds);
    }

    @McpTool(name = "listConfig", description = PrjxpP2ToolDocs.LIST_CONFIG)
    public String listConfig(
            @McpToolParam(description = "Max output tokens, default 2000.", required = false)
            Integer tokenBudget) {
        return toolService.listConfig(profile.boundProject(), tokenBudget);
    }

    @McpTool(name = "readChunks", description = PrjxpP2ToolDocs.READ_CHUNKS)
    public String readChunks(
            @McpToolParam(description = "Comma-separated chunk_ids, max 10; prefer 1–3.", required = true)
            String ids) {
        return toolService.readChunks(profile.boundProject(), ids);
    }
}
