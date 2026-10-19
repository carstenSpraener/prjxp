package de.spraener.prjxp.mcp.hub;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** DocumentSource backed by a DocPipe manifest file (JSON array of project-root-relative paths). */
public class ManifestDocumentSource implements DocumentSource {

    private final Path manifestPath;
    private final ObjectMapper objectMapper;

    public ManifestDocumentSource(Path manifestPath, ObjectMapper objectMapper) {
        this.manifestPath = manifestPath;
        this.objectMapper = objectMapper;
    }

    @Override
    public Stream<Path> documentStream() {
        if (!Files.isRegularFile(manifestPath)) {
            return Stream.empty();
        }
        try {
            List<String> paths = objectMapper.readValue(manifestPath.toFile(), new TypeReference<>() {});
            return paths.stream().map(Path::of);
        } catch (IOException e) {
            return Stream.empty();   // unreadable manifest → no documents (non-fatal by design)
        }
    }
}
