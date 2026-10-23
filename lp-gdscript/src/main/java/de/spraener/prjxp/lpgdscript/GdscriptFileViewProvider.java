package de.spraener.prjxp.lpgdscript;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.reader.FileViewProvider;
import de.spraener.prjxp.common.reader.ReaderSupport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Lossless file view for GDScript. The chunker emits a clean line tiling
 * (no synthetic skeletons), so sorting the units by {@code fromLine} and
 * concatenating their contents reproduces the file. Units whose line range
 * is contained in an already-emitted range are dropped (defensive against
 * overlapping units). Never returns null.
 */
public class GdscriptFileViewProvider implements FileViewProvider {

    @Override
    public String mimeType() {
        return GdscriptCodeChunker.GDSCRIPT_CODE_MIME_TYPE;
    }

    @Override
    public String language() {
        return "gdscript";
    }

    @Override
    public String render(List<PxChunk> units) {
        if (units == null || units.isEmpty()) {
            return "";
        }
        List<PxChunk> nonBlank = units.stream()
                .filter(u -> u != null && u.getContent() != null && !u.getContent().isBlank())
                .collect(Collectors.toCollection(ArrayList::new));
        nonBlank.sort(Comparator
                .comparingInt((PxChunk u) -> ReaderSupport.parseLine(u.getFromLine()))
                .thenComparing(Comparator.comparingInt((PxChunk u) -> ReaderSupport.parseLine(u.getToLine())).reversed())
                .thenComparing(PxChunk::getId));

        List<PxChunk> kept = new ArrayList<>();
        for (PxChunk u : nonBlank) {
            int from = ReaderSupport.parseLine(u.getFromLine());
            int to = ReaderSupport.parseLine(u.getToLine());
            boolean contained = kept.stream().anyMatch(k ->
                    ReaderSupport.parseLine(k.getFromLine()) <= from
                            && to <= ReaderSupport.parseLine(k.getToLine()));
            if (!contained) {
                kept.add(u);
            }
        }
        return kept.stream().map(PxChunk::getContent).collect(Collectors.joining("\n"));
    }
}
