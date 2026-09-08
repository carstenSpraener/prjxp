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
public class ByIndexController {

    private final ByIndexSearchService byIndexSearchService;
    private final SearchCapabilitiesRegistry registry;

    @GetMapping("/byIndex")
    @Operation(
            summary = "Deterministic symbol search over chunk index",
            description = """
                    SUCHE-TOOL: Exact, reproducible lookup of classes and methods via index metadata.
                    STRATEGIE: Use this for stable symbol lookups, e.g. to pin down a class or method after vectorSearch or grep.
                    PARAMETER-REGEL: Pass language plus at least one of fqn, methodName, signatureHash, containerFqn.
                    """)
    public ResponseEntity<?> byIndex(
            @Parameter(description = "Language (required, e.g. 'java').")
            @RequestParam(name = "language", required = false)
            String language,

            @Parameter(description = "Fully qualified name of a class or method (e.g. 'com.example.Foo#bar').")
            @RequestParam(name = "fqn", required = false)
            String fqn,

            @Parameter(description = "Optional symbol type filter (e.g. 'method', 'classFrame').")
            @RequestParam(name = "symbolType", required = false)
            String symbolType,

            @Parameter(description = "Optional method name.")
            @RequestParam(name = "methodName", required = false)
            String methodName,

            @Parameter(description = "Optional signature hash for exact matching.")
            @RequestParam(name = "signatureHash", required = false)
            String signatureHash,

            @Parameter(description = "Optional fully qualified name of the containing class.")
            @RequestParam(name = "containerFqn", required = false)
            String containerFqn,

            @Parameter(description = "Optional project name. Defaults to the active project.")
            @RequestParam(name = "project", required = false, defaultValue = "default")
            String project,

            @Parameter(description = "Maximum number of results (default 10, max 100).")
            @RequestParam(name = "limit", required = false, defaultValue = "" + SearchLimits.DEFAULT_LIMIT)
            int limit
    ) {
        if (language == null || language.isBlank()) {
            return badRequest("missingLanguage", "Parameter 'language' is required.");
        }
        if (isBlank(fqn) && isBlank(methodName) && isBlank(signatureHash) && isBlank(containerFqn)) {
            return badRequest("missingSearchParams", "Provide at least one of: fqn, methodName, signatureHash, containerFqn.");
        }
        if (registry.forLanguage(language).isEmpty()) {
            return badRequest("unknownLanguage", "Unknown language '" + language.trim()
                    + "'. Valid languages: " + String.join(", ", registry.byLanguage().keySet()));
        }

        ByIndexQuery query = new ByIndexQuery(
                language.trim(), fqn, symbolType, methodName, signatureHash, containerFqn, project,
                SearchLimits.clamp(limit));
        return ResponseEntity.ok(byIndexSearchService.search(query));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ResponseEntity<SearchError> badRequest(String error, String detail) {
        return ResponseEntity.badRequest().body(new SearchError(error, detail));
    }
}
