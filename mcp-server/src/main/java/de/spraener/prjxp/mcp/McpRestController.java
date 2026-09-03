package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/prjxp/tools")
@RequiredArgsConstructor
@Log
public class McpRestController {
    private final GRPromptEnrichment enrichment;
    private final PrjXPConfig cfg;

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
            String projectName) {
        String prefix = """
                """;
        if (projectName.equals("default")) {
            projectName = cfg.getActiveProject().get().getName();
        }

        log.info(String.format("searching context for '%s' for project '%s'.", userQuestion, projectName));
        String context = enrichment.enrich(projectName, userQuestion);
        String result = String.format("%s\n%s", prefix, context);
        log.info(String.format("    responding with %d chars (about %d tokens) of content", result.length(), result.length()/4));
        return result;
    }
}
