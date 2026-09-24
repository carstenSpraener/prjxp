package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.model.FileView;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.reader.FileViewProvider;
import de.spraener.prjxp.common.reader.ReaderSupport;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reconstructs a COMPLETE source view of one indexed file: lenient path resolution
 * (exact -> suffix -> basename) against the project index, dispatch to the
 * MIME-specific reader, paged line output and a character cap.
 */
@Service
@RequiredArgsConstructor
public class ReaderService {
    private static final int FILE_LOOKUP_LIMIT = 10_000;

    private final PxChunkDaoProvider chunkDaoProvider;
    private final PrjXPConfig cfg;
    private final FileViewRegistry fileViewRegistry;
    private final ProjectRegistry projectRegistry;

    private record Located(Optional<FileView> earlyExit, List<PxChunk> chunks) {
        static Located early(FileView view) {
            return new Located(Optional.of(view), null);
        }

        static Located chunks(List<PxChunk> chunks) {
            return new Located(Optional.empty(), chunks);
        }
    }

    public FileView read(String file, String project, Integer offset, Integer limit) {
        projectRegistry.ensureSearchable(project);   // throws UnknownProjectException for unknown projects
        String requested = ReaderSupport.normalizePath(file);
        PxChunkDao dao = resolveDao(project);
        if (dao == null)
            return FileView.error(file, "No embedding store available for project '" + project + "'.");

        Located located;
        try {
            located = locateFile(dao, requested, file);
        } catch (UnsupportedOperationException uoe) {
            return FileView.error(file, "Index search is not supported by this embedding store — readFile requires a Lucene index.");
        }
        if (located.earlyExit().isPresent()) return located.earlyExit().get();
        List<PxChunk> fileChunks = located.chunks();

        String mime = fileChunks.stream().map(PxChunk::getMimeType)
                .filter(Objects::nonNull).filter(s -> !s.isBlank()).findFirst().orElse("*/*");
        FileViewProvider provider = fileViewRegistry.forMime(mime);
        String content = provider.render(ReaderSupport.combineUnits(fileChunks));

        List<String> lines = content.lines().toList();
        int off = (offset == null || offset < 1) ? 1 : offset;
        if (off > lines.size()) return new FileView(requested, provider.language(), fileChunks.size(), "", false, lines.size(), 0, null, null);
        int to = (limit == null || limit < 1) ? lines.size() : Math.min(lines.size(), off - 1 + limit);
        String text = String.join("\n", lines.subList(off - 1, to));

        int max = cfg.getReaderMaxOutputChars();
        boolean truncated = false;
        if (max > 0 && text.length() > max) { text = text.substring(0, max); truncated = true; }

        return new FileView(requested, provider.language(), fileChunks.size(), text, truncated,
                lines.size(), to - off + 1, null, null);
    }

    private PxChunkDao resolveDao(String project) {   // same pattern as ByIndexSearchService
        String resolved = projectRegistry.resolve(project);
        return chunkDaoProvider.get(resolved).orElse(null);
    }

    private Located locateFile(PxChunkDao dao, String requested, String rawRequested) {
        // 1. exact terms: try requested and "/" + requested (index stores rootDir-relative paths WITH leading slash)
        for (String term : List.of(requested, "/" + requested).stream().distinct().toList()) {
            List<ScoredChunk> hits = dao.searchByIndex(Map.of(PxChunk.PXCHUNK_FILE, term), FILE_LOOKUP_LIMIT);
            if (!hits.isEmpty()) {
                Set<String> files = hits.stream().map(h -> ReaderSupport.normalizePath(h.chunk().getFile()))
                        .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
                if (files.size() == 1) return Located.chunks(hits.stream().map(ScoredChunk::chunk).toList());
                if (files.size() > 1)  return Located.early(FileView.ambiguous(rawRequested, List.copyOf(files)));
            }
        }
        // 2. MatchAll fallback: distinct file values, then suffix / basename match in memory
        Set<String> allFiles = new LinkedHashSet<>();
        Map<String, List<PxChunk>> byFile = new HashMap<>();
        for (ScoredChunk h : dao.searchByIndex(Map.of(), FILE_LOOKUP_LIMIT)) {
            String n = ReaderSupport.normalizePath(h.chunk().getFile());
            if (n == null) continue;
            allFiles.add(n);
            byFile.computeIfAbsent(n, k -> new ArrayList<>()).add(h.chunk());
        }
        List<String> matches = allFiles.stream()
                .filter(f -> f.equals(requested) || f.endsWith("/" + requested)
                             || f.substring(f.lastIndexOf('/') + 1).equals(requested.substring(requested.lastIndexOf('/') + 1)))
                .toList();
        if (matches.size() > 1)  return Located.early(FileView.ambiguous(rawRequested, matches));
        if (matches.size() == 0) return Located.early(FileView.error(rawRequested, "No file matching '" + requested + "' in the index."));
        return Located.chunks(byFile.get(matches.get(0)));
    }
}
