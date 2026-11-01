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
import java.util.HashSet;
import java.util.Set;

/**
 * Stateless, read-only change detector for LIVE projects. Computes the three-way split
 * against the {@code generatedAt} watermark (content-capture time of the last run):
 * <ul>
 *   <li>{@code toEmbed} — on-disk files with {@code mtime > generatedAt} OR not yet in the index (new).</li>
 *   <li>{@code toDelete} — in-index files no longer on disk, veto-passing, and NOT docpipe-generated.</li>
 * </ul>
 * Never writes. The watermark is read from the FIRST line of the live JSONL (one run = one timestamp).
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

    /** Result of a scan. {@code generatedAt} is the watermark used (0 = first scan / full update). */
    public record ScanResult(Set<String> toEmbed, Set<String> toDelete, long generatedAt) {
        public boolean isEmpty() { return toEmbed.isEmpty() && toDelete.isEmpty(); }
    }

    public ScanResult scan(ProjectEntry entry) {
        ProjectDefinition def = entry.getDefinition();
        if (!def.isLiveUpdate()) {
            return new ScanResult(Set.of(), Set.of(), 0);   // per-project opt-out
        }
        long generatedAt = readWatermark(entry);
        Set<String> diskFiles = walkSourceTree(entry);
        Set<String> indexFiles = luceneStore.listFilesByProject(entry.getName(), null);
        Set<String> docPipeFiles = luceneStore.listFilesByProject(entry.getName(),
                new IsEqualTo(PxChunk.metadataFieldKey(DocumentSource.DOCPIPE_GENERATED), "true"));

        Set<String> toEmbed = new HashSet<>();
        for (String file : diskFiles) {
            if (mtimeOf(entry, file) > generatedAt || !indexFiles.contains(file)) {
                toEmbed.add(file);
            }
        }
        Set<String> toDelete = new HashSet<>();
        for (String file : indexFiles) {
            if (!diskFiles.contains(file) && !docPipeFiles.contains(file) && passesVeto(entry, file)) {
                toDelete.add(file);
            }
        }
        return new ScanResult(toEmbed, toDelete, generatedAt);
    }

    /** The live-embedded JSONL path: {@code <liveJsonlDir>/<name>.live-embedded.jsonl}. */
    public Path liveJsonlPath(ProjectEntry entry) {
        return Path.of(props.getLiveJsonlDir(), entry.getName() + ".live-embedded.jsonl");
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

    /** Veto-passing source files as project-relative paths (mirrors the chunker's selection). */
    private Set<String> walkSourceTree(ProjectEntry entry) {
        Path root = entry.getRootDir().toAbsolutePath().normalize();
        ProjectDefinition def = entry.getDefinition();
        Set<String> files = new HashSet<>();
        if (!Files.isDirectory(root)) { return files; }
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> !vetoRegistry.shouldVeto(p, def))
                  .forEach(p -> files.add(toProjectRelativePath(root, p)));
        } catch (IOException e) {
            log.warn("Could not walk source tree of {}: {}", entry.getName(), e.toString());
        }
        return files;
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
