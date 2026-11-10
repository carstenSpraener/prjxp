package de.spraener.prjxp.chuno;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.spraener.prjxp.chuno.spring.SpringPreWalkEvent;
import de.spraener.prjxp.chuno.veto.VetoRegistry;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.graph.CodeGraphIo;
import de.spraener.prjxp.common.graph.GraphMetadata;
import de.spraener.prjxp.common.language.CodeGraphBuilder;
import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackRegistry;
import de.spraener.prjxp.common.model.CodeGraph;
import de.spraener.prjxp.common.model.GraphNode;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.transfer.TransferCrypto;
import de.spraener.prjxp.common.transfer.TransferPasswordResolver;
import de.spraener.prjxp.common.transfer.TransferSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Log
@RequiredArgsConstructor
public class ChunkProcess {
    private final PxLogService logService;
    private final ChunkerFactory factory;
    private final ApplicationEventPublisher eventPublisher;
    private final VetoRegistry vetoRegistry;
    private final Set<String> processedFiles = new HashSet<>();
    private final JsonMapper jsonMapper = new JsonMapper();
    private final PrjXPConfig cfg;
    private final TransferPasswordResolver transferPasswordResolver;
    private final LanguagePackRegistry languagePackRegistry;

    public void execute() throws Exception {
        final ProjectDefinition pd = cfg.getActiveProject().orElseThrow(() -> new IllegalStateException(
                "No active project '" + cfg.getActiveProjectName() + "' defined. Available projects: "
                        + cfg.getProjects().stream().map(ProjectDefinition::getName).toList()));
        executeForProject(pd);
    }

    /** Runs the chunking pipeline for an explicitly given project definition (hub in-process use). */
    public void executeForProject(ProjectDefinition pd) throws Exception {
        processedFiles.clear();   // per-run state: a reindex must re-chunk everything, not filter out files of a previous run
        final TransferSession session = transferPasswordResolver.prepare(
                cfg.getTransfer().getEncrypt(), cfg.getTransfer().getPasswordEnv());
        final PrintStream out = createWriter(pd, session);
        eventPublisher.publishEvent(new SpringPreWalkEvent<>(cfg, pd));

        Files.walk(Path.of(pd.getRootDir()))
                .filter(Files::isRegularFile)
                .filter(path -> checkVetos(path, pd))
                .filter(path -> !processedFiles.contains(path.toAbsolutePath().toString()))
                .forEach(path -> handlePath(out, pd, path));
        ;
        doPostWalk(out);
        writeGraphArtifact(pd, session);
        if (out != System.out) {
            out.close();
        }
        if (session.generatedPassword()) {
            transferPasswordResolver.printRepeatBanner(session.password());
        }
    }

    /**
     * Chunks an explicit list of files (no tree walk, no JSONL output) and returns the chunks in memory.
     * Used by the hub's document-embedding runs (DocPipe-generated documents). Vetos are evaluated
     * against the given definition — callers that need markdown must ensure "md" is in its whitelist.
     * Files that are not regular files or are vetoed are skipped silently.
     */
    public List<PxChunk> executeForFiles(ProjectDefinition pd, List<Path> files) {
        final Path rootPath = Path.of(pd.getRootDir()).toAbsolutePath().normalize();
        return files.stream()
                .filter(Files::isRegularFile)
                .filter(path -> checkVetos(path, pd))
                .flatMap(path -> factory.createChunker(path.toFile())
                        .parallel()
                        .flatMap(chunker -> chunker.chunk(path.toFile())))
                .map(chunk -> {
                    if (chunk.getFile() != null) {
                        chunk.setFile(toProjectRelativePath(rootPath, chunk.getFile()));
                    }
                    return chunk;
                })
                .toList();
    }

    private PrintStream createWriter(ProjectDefinition pd, TransferSession session) {
        if( pd.resolvedJsonlFile()==null ) {
            return System.out;
        }
        try {
            Path target = Path.of(pd.resolvedJsonlFile());
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            OutputStream out = new FileOutputStream(pd.resolvedJsonlFile());
            if (session.encrypt()) {
                out = TransferCrypto.openEncryptedOutputStream(out, session.password());
            }
            return new PrintStream(out);
        } catch( FileNotFoundException fnfXC) {
            log.warning("Could not find output file "+pd.resolvedJsonlFile()+". Using stdout. Error is: "+fnfXC.getMessage());
            return System.out;
        } catch( IOException ioXC) {
            log.warning("Could not open output file "+pd.resolvedJsonlFile()+". Using stdout. Error is: "+ioXC.getMessage());
            return System.out;
        }
    }

    protected boolean checkVetos(Path p) {
        return !vetoRegistry.shouldVeto(p);
    }

    /** Like {@link #checkVetos(Path)} but evaluates vetos in the context of a specific project definition. */
    protected boolean checkVetos(Path p, ProjectDefinition pd) {
        return !vetoRegistry.shouldVeto(p, pd);
    }

    protected void handlePath(PrintStream out, ProjectDefinition pd, Path p) {
        final Path rootPath = Path.of(pd.getRootDir()).toAbsolutePath().normalize();
        factory.createChunker(p.toFile())
                .filter(c -> !(c instanceof FullTextChunker) || pd.isFulltext())
                .parallel()
                .flatMap(c -> c.chunk(p.toFile()))
                .map( chunk -> {
                    String relPath = toProjectRelativePath(rootPath, chunk.getFile());
                    chunk.setFile(relPath);
                    rebaseIdToProjectPath(chunk, relPath, p.getFileName().toString());
                    return chunk;
                })
                .map(chunk -> toJSONL(chunk))
                .forEach(
                        str -> out.println(str)
                )
        ;
        out.flush();
        processedFiles.add(p.toAbsolutePath().toString());
    }

    private String toProjectRelativePath(Path rootPath, String chunkFile) {
        if (chunkFile == null || chunkFile.isBlank()) {
            return chunkFile;
        }
        Path rawPath = Path.of(chunkFile);
        Path normalizedPath = rawPath.isAbsolute() ? rawPath.normalize() : rootPath.resolve(rawPath).normalize();
        Path relativePath;
        try {
            relativePath = rootPath.relativize(normalizedPath);
        } catch (IllegalArgumentException ignored) {
            // Different roots (e.g. different drive letters on Windows): keep normalized absolute path.
            relativePath = normalizedPath;
        }
        return relativePath.toString().replace(File.separatorChar, '/');
    }

    /**
     * Rebases a basename-based chunk ID onto the project-relative path so that same-named
     * files in different directories (e.g. {@code README.md} in many sub-projects) don't
     * collide on the same ID. Without this, tibed's {@code StoreIdChecker} would silently
     * drop every chunk whose ID already exists in the store.
     * <p>
     * Only IDs that start with the file's basename are rebased (e.g. {@code README.md:1:2}
     * → {@code core/cgv19-cli/README.md:1:2}). IDs that are not basename-based — absolute
     * paths (FullText/Text chunkers) or FQNs (Java/TS chunkers) — are left untouched.
     */
    private void rebaseIdToProjectPath(PxChunk chunk, String relPath, String basename) {
        String id = chunk.getId();
        if (id == null || id.isBlank() || relPath == null || relPath.isBlank()
                || basename == null || basename.isBlank()) {
            return;
        }
        if (id.equals(basename)) {
            chunk.setId(relPath);
        } else if (id.startsWith(basename)) {
            chunk.setId(relPath + id.substring(basename.length()));
        }
    }

    protected void doPostWalk(PrintStream out) {
        Map<String, GraphNode> graphNodes = weightedGraphNodes();
        factory.listPostWalkChunker()
                .parallel()
                .flatMap(pwChunk -> pwChunk.chunk(null))
                .map(chunk -> {
                    enrichWithLeiden(chunk, graphNodes);
                    return toJSONL(chunk);
                })
                .forEach(
                        str -> out.println(str)
                )
        ;
        out.flush();
    }

    /** The first language pack that produced a weighted graph this run, or null. */
    private CodeGraph weightedGraph() {
        if (languagePackRegistry == null) return null;
        for (LanguagePack pack : languagePackRegistry.all()) {
            if (pack == null) continue;
            Optional<CodeGraphBuilder> builder = pack.codeGraph();
            if (builder != null && builder.isPresent()) {
                Optional<CodeGraph> graph = builder.get().weightedGraph();
                if (graph != null && graph.isPresent()) return graph.get();
            }
        }
        return null;
    }

    private Map<String, GraphNode> weightedGraphNodes() {
        CodeGraph graph = weightedGraph();
        return graph == null ? Map.of() : graph.nodeById();
    }

    /** Stamps Leiden cluster/weight onto a post-walk chunk from its parent type node (method nodes are skipped). */
    private void enrichWithLeiden(PxChunk chunk, Map<String, GraphNode> graphNodes) {
        if (chunk == null || chunk.getParent() == null || graphNodes.isEmpty()) return;
        GraphNode node = graphNodes.get(chunk.getParent());
        if (node == null || node.isMethod()) return;
        if (node.clusterId() != null) chunk.getMetadata().put(GraphMetadata.LEIDEN_CLUSTER_ID, node.clusterId());
        chunk.getMetadata().put(GraphMetadata.LEIDEN_WEIGHT, String.valueOf(node.weight()));
    }

    /** Writes the weighted code graph to the project's graph artifact (optionally encrypted). */
    private void writeGraphArtifact(ProjectDefinition pd, TransferSession session) {
        CodeGraph graph = weightedGraph();
        if (graph == null || graph.nodes().isEmpty()) return;
        String targetPath = pd.resolvedGraphFile();
        if (targetPath == null || targetPath.isBlank()) return;
        try {
            Path target = Path.of(targetPath);
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try (OutputStream raw = new FileOutputStream(targetPath)) {
                OutputStream out = session.encrypt()
                        ? TransferCrypto.openEncryptedOutputStream(raw, session.password())
                        : raw;
                new CodeGraphIo().write(out, graph);
                if (out != raw) out.close();   // emit cipher final block + GCM auth tag
            }
        } catch (IOException | RuntimeException e) {
            log.warning("Could not write graph artifact " + targetPath + ": " + e.getMessage());
        }
    }

    public String toJSONL(PxChunk chunk) {
        try {
            return jsonMapper.writeValueAsString(chunk);
        } catch (JsonProcessingException jpXC) {
            logService.error(jpXC, "Could not write jsonl for chunk %s", chunk.getId());
            return "";
        }
    }
}
