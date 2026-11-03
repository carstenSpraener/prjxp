package de.spraener.prjxp.mcp.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.chuno.veto.VetoRegistry;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.EmbeddedChunkRecord;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Stateless, read-only change detector for LIVE projects. Computes the three-way split
 * against a persisted per-file state. Existing installations without that state temporarily
 * fall back to the {@code generatedAt} watermark from the live JSONL:
 * <ul>
 *   <li>{@code toEmbed} — on-disk files with {@code mtime > generatedAt} OR not yet in the index (new).</li>
 *   <li>{@code toDelete} — in-index files no longer on disk, veto-passing, and NOT docpipe-generated.</li>
 * </ul>
 * State is published only after a successful index update. This avoids an endless update loop when
 * a bind-mounted file has an mtime ahead of the container clock, and also supports empty indexes.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class LiveUpdateScanner {

    private static final Logger log = LoggerFactory.getLogger(LiveUpdateScanner.class);

    private final HubProperties props;
    private final VetoRegistry vetoRegistry;
    private final LuceneEmbeddingStore luceneStore;
    private final ObjectMapper objMapper;

    /** A lightweight, persisted file version. */
    public record FileState(long modifiedAt, long size) { }

    /** Persisted state of a successfully indexed source-tree view. */
    public record SourceState(long capturedAt, Map<String, FileState> files) { }

    /** Result of a scan. {@code generatedAt} is the legacy watermark used (0 = first scan / state mode). */
    public record ScanResult(Set<String> toEmbed, Set<String> toDelete, long generatedAt,
                             Map<String, FileState> sourceState) {
        public ScanResult(Set<String> toEmbed, Set<String> toDelete, long generatedAt) {
            this(toEmbed, toDelete, generatedAt, Map.of());
        }
        public boolean isEmpty() { return toEmbed.isEmpty() && toDelete.isEmpty(); }
    }

    public ScanResult scan(ProjectEntry entry) {
        ProjectDefinition def = entry.getDefinition();
        if (!def.isLiveUpdate()) {
            return new ScanResult(Set.of(), Set.of(), 0);   // per-project opt-out
        }
        SourceState previousState = readSourceState(entry);
        long generatedAt = previousState == null ? readWatermark(entry) : 0;
        Map<String, FileState> diskState = captureSourceState(entry);
        Set<String> diskFiles = diskState.keySet();
        Set<String> indexFiles = luceneStore.listFilesByProject(entry.getName(), null);
        Set<String> docPipeFiles = luceneStore.listFilesByProject(entry.getName(),
                new IsEqualTo(PxChunk.metadataFieldKey(DocumentSource.DOCPIPE_GENERATED), "true"));

        Set<String> toEmbed = new HashSet<>();
        for (String file : diskFiles) {
            boolean changed = previousState != null
                    ? !diskState.get(file).equals(previousState.files().get(file))
                    : mtimeOf(entry, file) > generatedAt;
            // After a successful state publication, a missing index entry can be intentional (for
            // example an unsupported or empty file that produced no chunks). Only the first scan
            // uses the index as a completeness signal; later scans follow the source state.
            if (changed || (previousState == null && !indexFiles.contains(file))) {
                toEmbed.add(file);
            }
        }
        Set<String> toDelete = new HashSet<>();
        for (String file : indexFiles) {
            if (!diskFiles.contains(file) && !docPipeFiles.contains(file) && passesVeto(entry, file)) {
                toDelete.add(file);
            }
        }
        return new ScanResult(toEmbed, toDelete, generatedAt, Map.copyOf(diskState));
    }

    /** The live-embedded JSONL path: {@code <liveJsonlDir>/<name>.live-embedded.jsonl}. */
    public Path liveJsonlPath(ProjectEntry entry) {
        return Path.of(props.getLiveJsonlDir(), entry.getName() + ".live-embedded.jsonl");
    }

    /** Sidecar state path, deliberately independent of the (possibly empty) embedded JSONL. */
    public Path liveStatePath(ProjectEntry entry) {
        return Path.of(props.getLiveJsonlDir(), entry.getName() + ".live-state.json");
    }

    /** Captures all veto-passing source files and their lightweight versions. */
    public Map<String, FileState> captureSourceState(ProjectEntry entry) {
        Path root = entry.getRootDir().toAbsolutePath().normalize();
        ProjectDefinition def = entry.getDefinition();
        Map<String, FileState> files = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) { return files; }
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !vetoRegistry.shouldVeto(p, def))
                    .forEach(p -> {
                        try {
                            files.put(toProjectRelativePath(root, p), new FileState(
                                    Files.getLastModifiedTime(p).toMillis(), Files.size(p)));
                        } catch (IOException e) {
                            log.debug("Skipping unreadable source file {} while scanning {}: {}", p, entry.getName(), e.toString());
                        }
                    });
        } catch (IOException e) {
            log.warn("Could not walk source tree of {}: {}", entry.getName(), e.toString());
        }
        return files;
    }

    /** Atomically records the source view that was successfully submitted for indexing. */
    public void publishSourceState(ProjectEntry entry, Map<String, FileState> files) throws IOException {
        Path target = liveStatePath(entry);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        if (target.getParent() != null) { Files.createDirectories(target.getParent()); }
        objMapper.writeValue(tmp.toFile(), new SourceState(System.currentTimeMillis(), files == null ? Map.of() : files));
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The current watermark ({@code generatedAt} of the first live-JSONL line); 0 when absent. */
    public long watermarkOf(ProjectEntry entry) {
        return readWatermark(entry);
    }

    /** Watermark = {@code generatedAt} of the first JSONL line; 0 when the file is absent/unreadable. */
    private long readWatermark(ProjectEntry entry) {
        Path jsonl = liveJsonlPath(entry);
        if (!Files.isRegularFile(jsonl)) {
            return 0;   // no JSONL yet -> first scan -> full update
        }
        try (var lines = Files.lines(jsonl)) {
            String first = lines.filter(l -> !l.isBlank()).findFirst().orElse(null);
            if (first == null) { return 0; }
            EmbeddedChunkRecord record = objMapper.readValue(first, EmbeddedChunkRecord.class);
            return record.generatedAt() != null ? record.generatedAt() : 0;
        } catch (Exception e) {
            log.warn("Could not read watermark from {} — treating as first scan: {}", jsonl, e.toString());
            return 0;
        }
    }

    private SourceState readSourceState(ProjectEntry entry) {
        Path state = liveStatePath(entry);
        if (!Files.isRegularFile(state)) {
            return null;
        }
        try {
            SourceState result = objMapper.readValue(state.toFile(), SourceState.class);
            return result.files() == null ? null : result;
        } catch (Exception e) {
            log.warn("Could not read live source state from {} — using legacy watermark once: {}", state, e.toString());
            return null;
        }
    }

    private long mtimeOf(ProjectEntry entry, String relFile) {
        try {
            return Files.getLastModifiedTime(entry.getRootDir().resolve(relFile)).toMillis();
        } catch (IOException e) {
            return 0;   // gone -> not embeddable (caught as deleted if it is in the index)
        }
    }

    private boolean passesVeto(ProjectEntry entry, String relFile) {
        Path abs = entry.getRootDir().resolve(relFile);
        return !vetoRegistry.shouldVeto(abs, entry.getDefinition());
    }

    /** Mirrors {@code ChunkProcess.toProjectRelativePath}: project-relative, forward slashes. */
    private String toProjectRelativePath(Path rootPath, Path absPath) {
        Path normalized = absPath.toAbsolutePath().normalize();
        Path relative;
        try {
            relative = rootPath.relativize(normalized);
        } catch (IllegalArgumentException e) {
            relative = normalized;
        }
        return relative.toString().replace(File.separatorChar, '/');
    }
}
