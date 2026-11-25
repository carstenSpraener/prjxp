package de.spraener.prjxp.mcp.docs;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequestMapping("/api/docs")
@RequiredArgsConstructor
public class DocsController {

    private final DocsService docsService;

    @GetMapping("/tree")
    @Operation(description = "Documentation tree: folders, markdown documents, H2 chapters.")
    public DocsNode tree() {
        return docsService.buildTree();
    }

    @GetMapping("/content")
    @Operation(description = "Raw markdown of a document (path relative to the docs root).")
    public ResponseEntity<String> content(@RequestParam String path) {
        return docsService.readContent(path)
                .map(md -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                        .body(md))
                .orElseGet(() -> ResponseEntity.<String>notFound().build());
    }

    @GetMapping("/file")
    @Operation(description = "Static asset from the docs root (images etc.).")
    public ResponseEntity<byte[]> file(@RequestParam String path) {
        return docsService.readAsset(path)
                .map(a -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(a.contentType()))
                        .body(a.data()))
                .orElseGet(() -> ResponseEntity.<byte[]>notFound().build());
    }
}
