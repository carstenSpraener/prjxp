package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.capability.SearchParamDef;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/prjxp/tools")
@RequiredArgsConstructor
public class MetaSearchParamsController {

    public static final String SCHEMA_VERSION = "1.0";

    private final SearchCapabilitiesRegistry registry;

    @GetMapping("/meta-search-params")
    @Operation(
            summary = "Machine-readable search parameters",
            description = """
                    META-TOOL: Describes all valid parameters of the search endpoints (grep, byIndex).
                    STRATEGIE: Call this once before using language-specific search parameters to learn the valid values.
                    """)
    public ResponseEntity<MetaSearchParamsResponse> metaSearchParams() {
        return ResponseEntity.ok(new MetaSearchParamsResponse(
                SCHEMA_VERSION,
                globalParams(),
                registry.byLanguage()));
    }

    private List<SearchParamDef> globalParams() {
        return List.of(
                SearchParamDef.optional("project", "Optional project name. Defaults to the active project."),
                SearchParamDef.optional("limit", String.format(
                        "Maximum number of results (default %d, max %d).", SearchLimits.DEFAULT_LIMIT, SearchLimits.MAX_LIMIT)),
                SearchParamDef.optional("mode", "Optional search mode (e.g. 'exact' or 'fuzzy'). Not supported by all endpoints."));
    }
}
