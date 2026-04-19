package de.spraener.prjxp.mcp;

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

    @GetMapping("/ping")
    @Operation(description = "Answers a request with 'pong!' in order to check network functionality.")
    public String ping() {
        return "pong!";
    }

    @GetMapping("/context")
    @Operation(
            summary =  "Liefert Relevanten Kontext aus den Projekten passend zur Frage des Benutzers.",
            description = "Liefert Relevanten Kontext aus den Projekten passend zur Frage des Benutzers.",
            operationId="readRelevantSource"
    )
    public String readRelevantSource(
            @Parameter(description = "Die ursprüngliche Frage des Benutzers, zu der Information von den Projekten benötigt wird.")
            @RequestParam(name = "userQuestion", required = true) String userQuestion
    ) {
        return enrichment.enrich(userQuestion);
    }
}
