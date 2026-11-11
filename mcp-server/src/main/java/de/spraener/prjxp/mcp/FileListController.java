package de.spraener.prjxp.mcp;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class FileListController {

    private final FileListService fileListService;

    @GetMapping("/files")
    @Operation(
            summary = "List files in the project index",
            description = """
                    STRUCTURAL DISCOVERY (orthogonal to the search ladder): distinct file paths matching a prefix or glob.
                    '*' matches any characters including '/' (recursive).
                    """)
    public ResponseEntity<?> listFiles(
            @Parameter(description = "Path prefix or glob. Empty = all files.")
            @RequestParam(name = "path", required = false, defaultValue = "")
            String path,

            @Parameter(description = "Required project name. Call the projects endpoint to discover valid names.")
            @RequestParam(name = "project", required = true)
            String project,

            @Parameter(description = "Maximum number of files (default 50, max 500).")
            @RequestParam(name = "limit", required = false, defaultValue = "" + SearchLimits.LIST_DEFAULT_LIMIT)
            int limit
    ) {
        int safeLimit = SearchLimits.clampList(limit);
        try {
            return ResponseEntity.ok(fileListService.list(path, project, safeLimit));
        } catch (UnknownProjectException e) {
            return ResponseEntity.badRequest().body(new SearchError("unknownProject", e.getMessage()));
        }
    }
}
