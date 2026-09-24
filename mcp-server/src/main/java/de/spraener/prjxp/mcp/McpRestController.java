package de.spraener.prjxp.mcp;

import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/prjxp/tools")
@RequiredArgsConstructor
@Log
public class McpRestController {
    private final GRPromptEnrichment enrichment;
    private final ProjectRegistry projectRegistry;

    @GetMapping("/ping")
    @Operation(description = "Answers a request with 'pong!' in order to check network functionality.")
    public String ping() {
        return "pong!";
    }

    @GetMapping("/context")
    @Operation(
            description = """
                    SUCHE-TOOL: Delivers relevant information from the project. 
                    STRATEGIE: You should call this tool whenever possible to gather information before searching the file system.
                    You can do multiple follow up questions for more detailed information 
                    PARAMETER-REGEL: Build a precise question for a vector search based on the information
                    you are looking for.
                    """,
            operationId = "readRelevantSource"
    )
    public String readRelevantSource(
            @Parameter(description = "A targeted, standalone search prompt optimized for vector retrieval based on what you need to find.")
            @RequestParam(name = "userQuestion", required = true)
            String userQuestion,

            @RequestParam(name = "project", required = false, defaultValue = "default")
            String projectName,

            @Parameter(description = "Similarity threshold 0.0-1.0 (default 0.85).")
            @RequestParam(name = "similarity", required = false) Double similarity,

            @Parameter(description = "Maximum results 1-20 (default 20).")
            @RequestParam(name = "maxResults", required = false) Integer maxResults,

            @Parameter(description = "Return class skeletons instead of full method context (default false).")
            @RequestParam(name = "skeletonsOnly", required = false) Boolean skeletonsOnly) {
        String prefix = """
                """;
        try {
            projectRegistry.ensureSearchable(projectName);
        } catch (UnknownProjectException e) {
            return "ERROR: " + e.getMessage();
        }
        String resolved = projectRegistry.resolve(projectName);

        log.info(String.format("searching context for '%s' for project '%s'.", userQuestion, resolved));
        String context;
        if (similarity == null && maxResults == null && skeletonsOnly == null) {
            context = enrichment.enrich(resolved, userQuestion);   // legacy behavior unchanged
        } else {
            if (similarity == null || similarity < 0.0 || similarity > 1.0) similarity = 0.85;
            if (maxResults == null || maxResults < 1 || maxResults > 20) maxResults = 20;
            if (skeletonsOnly == null) skeletonsOnly = false;
            context = enrichment.enrich(resolved, userQuestion, similarity, maxResults, skeletonsOnly);
        }
        String result = String.format("%s\n%s", prefix, context);
        log.info(String.format("    responding with %d chars (about %d tokens) of content", result.length(), result.length()/4));
        return result;
    }

    /**
     * Convenience overload without search parameters — delegates with defaults (legacy behavior).
     */
    public String readRelevantSource(String userQuestion, String projectName) {
        return readRelevantSource(userQuestion, projectName, null, null, null);
    }

    @GetMapping("projects")
    @Operation(description = "Returns a list of all available projects.")
    public List<String> listProjects() {
        return projectRegistry.availableProjects();
    }
}
