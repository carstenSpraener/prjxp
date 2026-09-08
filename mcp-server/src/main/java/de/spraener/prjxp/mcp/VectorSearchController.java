package de.spraener.prjxp.mcp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/prjxp/tools")
@RequiredArgsConstructor
public class VectorSearchController {

    private final VectorSearchService vectorSearchService;

    @GetMapping("/vectorSearch")
    @Operation(
            summary = "Semantic vector search over project chunks",
            description = """
                    SUCHE-TOOL: Semantic vector search over all chunks of a project, ranked by similarity.
                    STRATEGIE: Use this FIRST for a broad overview, then narrow down with grep (exact text) or byIndex (symbols).
                    PARAMETER-REGEL: Pass a targeted, standalone search query. Optionally filter by language and project.
                    """)
    public ResponseEntity<?> vectorSearch(
            @Parameter(description = "Semantic search query (required).")
            @RequestParam(name = "query", required = false)
            String query,

            @Parameter(description = "Optional project name. Defaults to the active project.")
            @RequestParam(name = "project", required = false, defaultValue = "default")
            String project,

            @Parameter(description = "Optional language filter (e.g. 'java').")
            @RequestParam(name = "language", required = false)
            String language,

            @Parameter(description = "Maximum number of results (default 10, max 100).")
            @RequestParam(name = "limit", required = false, defaultValue = "" + SearchLimits.DEFAULT_LIMIT)
            int limit
    ) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(new SearchError("missingQuery", "Parameter 'query' is required."));
        }

        int safeLimit = SearchLimits.clamp(limit);
        return ResponseEntity.ok(vectorSearchService.search(query.trim(), project, language, safeLimit));
    }
}
