package de.spraener.prjxp.mcp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/prjxp/tools")
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
            @RequestParam(name = "query", required = false)
            String query,

            @Parameter(description = "Optional project name. Defaults to the active project.")
            @RequestParam(name = "project", required = false, defaultValue = "default")
            String project,

            @Parameter(description = "Optional language filter (e.g. 'java', 'ts').")
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
        return ResponseEntity.ok(grepSearchService.search(query.trim(), project, language, safeLimit));
    }
}
