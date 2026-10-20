package de.spraener.prjxp.docpipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.ProjectDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes the per-run manifest of generated documents: a JSON array of paths relative to the
 * project root (forward slashes). The hub's DocumentSource reads it after a run.
 */
@Component
@RequiredArgsConstructor
public class DocPipeManifestWriter {

    public static final String MANIFEST_RELATIVE_PATH = ".prjxp-data/docpipe/manifest.json";

    private final ObjectMapper objectMapper;

    public Path manifestPathFor(ProjectDefinition pd) {
        return Path.of(pd.getRootDir()).resolve(MANIFEST_RELATIVE_PATH);
    }

    /** Writes (overwrites) the manifest. Paths outside the project root are kept absolute. */
    public void write(ProjectDefinition pd, List<String> absoluteOutputPaths) throws IOException {
        Path root = Path.of(pd.getRootDir()).toAbsolutePath().normalize();
        List<String> relative = absoluteOutputPaths.stream()
                .map(p -> {
                    Path abs = Path.of(p).toAbsolutePath().normalize();
                    if (abs.startsWith(root)) {
                        return root.relativize(abs).toString().replace(File.separatorChar, '/');
                    }
                    return abs.toString();   // outside project root — keep absolute
                })
                .sorted()
                .toList();
        Path manifest = manifestPathFor(pd);
        Files.createDirectories(manifest.getParent());
        objectMapper.writeValue(manifest.toFile(), relative);
    }
}
