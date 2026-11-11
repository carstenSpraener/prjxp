package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.retrieval.ContentLevel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class GrepSearchController {

    private final GrepSearchService grepSearchService;

    @GetMapping("/grep")
    @Operation(
            summary = "Full-text search over project chunks",
            description = """
                    SUCHE-TOOL: Exact full-text search over all chunks of a project.
                    STRATEGIE: Use this to narrow down results after vectorSearch, e.g. for exact identifiers or strings.
                    PARAMETER-REGEL: Pass the exact string you are looking for as query.
                    """)
    public ResponseEntity<?> grep(
            @Parameter(description = "Exact search string (required).")
            @RequestParam(name = "query", required = true)
            String query,

            @Parameter(description = "Required project name. Call the projects endpoint to discover valid names.")
            @RequestParam(name = "project", required = true)
            String project,

            @Parameter(description = "Optional language filter (e.g. 'java', 'ts').")
            @RequestParam(name = "language", required = false)
            String language,

            @Parameter(description = "Maximum number of results (default 10, max 100).")
            @RequestParam(name = "limit", required = false, defaultValue = "" + SearchLimits.DEFAULT_LIMIT)
            int limit,

            @Parameter(description = "How much content to emit per hit: FULL (default), SKELETON or POINTERS (tool hints only).")
            @RequestParam(name = "contentLevel", required = false)
            String contentLevel
    ) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(new SearchError("missingQuery", "Parameter 'query' is required."));
        }

        int safeLimit = SearchLimits.clamp(limit);
        try {
            return ResponseEntity.ok(grepSearchService.search(query.trim(), project, language, safeLimit, true,
                    ContentLevel.parse(contentLevel)));
        } catch (UnknownProjectException e) {
            return ResponseEntity.badRequest().body(new SearchError("unknownProject", e.getMessage()));
        }
    }
}
