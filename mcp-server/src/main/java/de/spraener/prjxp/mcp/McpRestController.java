package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.gldrtrvr.enrichment.GRPromptEnrichment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springaicommunity.mcp.annotation.McpTool;
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
            // summary =  "Liefert Relevanten Kontext aus den Projekten passend zur Frage des Benutzers.",
            description = """
                    SUCHE-TOOL: Liefert relevanten Kontext aus dem Projekt. 
                    STRATEGIE: Wenn die erste Antwort nicht ausreicht, rufe dieses Tool bis zu 3-mal iterativ auf. 
                    PARAMETER-REGEL: Nutze beim ersten Aufruf die User-Frage. Bei Folge-Aufrufen (Nachfragen) 
                    formuliere bitte eine eigene, technisch präzisere Suchanfrage als 'userQuestion', 
                    basierend auf den fehlenden Informationen aus dem vorherigen Schritt.
                    """,
            operationId = "readRelevantSource"
    )
    @McpTool(description = """
            SUCHE-TOOL: Liefert relevanten Kontext aus dem Projekt. 
            STRATEGIE: Formuliere eine konkrete Frage für eine Vektorsuche und übergebe diese Frage
            als Parameter "userQuestion". Wenn die erste Antwort nicht ausreicht, rufe dieses Tool bis zu 3-mal iterativ auf. 
            PARAMETER-REGEL: Nutze beim ersten Aufruf die User-Frage. Bei Folge-Aufrufen (Nachfragen) 
            formuliere bitte eine eigene, technisch präzisere Suchanfrage als 'userQuestion', 
            basierend auf den fehlenden Informationen aus dem vorherigen Schritt.
            """)
    public String readRelevantSource(
            @Parameter(description = "Die ursprüngliche Frage des Benutzers, zu der Information von den Projekten benötigt wird.")
            @RequestParam(name = "userQuestion", required = true) String userQuestion,
            @RequestParam(name = "project", required = false, defaultValue = "default") String projectName
    ) {
        String prefix = """
                """;
        if (projectName.equals("default")) {
            projectName = cfg.getActiveProject().get().getName();
        }

        log.info(String.format("searching context for '%s' for project '%s'.", userQuestion, projectName));
        String context = enrichment.enrich(projectName, userQuestion);
        String result = String.format("%s\n%s", prefix, context);
        System.out.println("returning context: "+result);
        return result;
    }
}
