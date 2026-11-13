package de.spraener.prjxp.tibed;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.transfer.TransferCrypto;
import de.spraener.prjxp.common.transfer.TransferPasswordResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the ast-neighbours sidecar written by chunk-norris (Phase 03).
 * Returns an empty map when the file is absent or unreadable.
 */
@Log
@Component
@RequiredArgsConstructor
public class AstNeighboursLoader {

    private static final String GRAPH_FILE_SUFFIX = ".graph.json";
    private static final String AST_NEIGHBOURS_FILE_SUFFIX = ".ast-neighbours.json";

    private final ObjectMapper objectMapper;
    private final TransferPasswordResolver passwordResolver;
    private final PrjXPConfig cfg;

    /**
     * Reads the sidecar for the given project. Path is derived from the graph file:
     * {@code px-chunks.graph.json} → {@code px-chunks.ast-neighbours.json}.
     * Returns an empty map when the file does not exist.
     */
    public Map<String, List<String>> load(ProjectDefinition pd) {
        if (pd == null) {
            return Map.of();
        }
        String graphPath = pd.resolvedGraphFile();
        if (graphPath == null || graphPath.isBlank()) {
            return Map.of();
        }
        Path path = Path.of(astNeighboursPath(graphPath));
        if (!Files.exists(path)) {
            return Map.of();
        }
        try {
            char[] password = passwordResolver.resolvePassword(cfg.getTransfer().getPasswordEnv());
            try (InputStream raw = Files.newInputStream(path);
                 InputStream in = TransferCrypto.openAuto(raw, password)) {
                Map<String, List<String>> sidecar = objectMapper.readValue(
                        in, new TypeReference<LinkedHashMap<String, List<String>>>() {});
                return sidecar == null ? Map.of() : sidecar;
            }
        } catch (IOException | RuntimeException e) {
            // non-fatal: chunks simply lack ast_neighbours
            log.warning("Could not read ast-neighbours artifact " + path + ": " + e.getMessage());
            return Map.of();
        }
    }

    /**
     * Derivative of the graph path for the sidecar, mirroring the writer in chunk-norris: a graph
     * artifact named {@code *.graph.json} is replaced, any other name is extended (so a custom
     * graph file can never be shadowed by its own sidecar).
     */
    static String astNeighboursPath(String graphPath) {
        if (graphPath.endsWith(GRAPH_FILE_SUFFIX)) {
            return graphPath.substring(0, graphPath.length() - GRAPH_FILE_SUFFIX.length())
                    + AST_NEIGHBOURS_FILE_SUFFIX;
        }
        return graphPath + AST_NEIGHBOURS_FILE_SUFFIX;
    }
}
