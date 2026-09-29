package de.spraener.prjxp.common.reader;

import de.spraener.prjxp.common.model.PxChunk;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class GenericFileViewProvider implements FileViewProvider {

    @Override
    public String mimeType() {
        return "*/*";
    }

    @Override
    public String language() {
        return "generic";
    }

    @Override
    public String render(List<PxChunk> units) {
        List<PxChunk> nonBlank = units.stream()
                // 1. Drop units with blank content (placeholder FILE/SECTION chunks, empty frames)
                .filter(unit -> unit.getContent() != null && !unit.getContent().isBlank())
                // 2. Sort by fromLine ASC (ReaderSupport.parseLine, 0 when unparseable),
                //    then toLine DESC (wider first), then id
                .sorted(Comparator
                        .comparingInt((PxChunk unit) -> ReaderSupport.parseLine(unit.getFromLine()))
                        .thenComparingInt(unit -> -ReaderSupport.parseLine(unit.getToLine()))
                        .thenComparing(PxChunk::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();

        // 3. Emit a unit only if its range [fromLine, toLine] is NOT contained in an already-emitted range
        //    (containment: from >= emitted.from && to <= emitted.to). This deduplicates nested units
        //    (e.g. methods inside a class frame) without a language-specific reader.
        List<PxChunk> emitted = new ArrayList<>();
        List<Range> emittedRanges = new ArrayList<>();
        for (PxChunk unit : nonBlank) {
            int from = ReaderSupport.parseLine(unit.getFromLine());
            int to = ReaderSupport.parseLine(unit.getToLine());
            boolean contained = emittedRanges.stream()
                    .anyMatch(range -> from >= range.from() && to <= range.to());
            if (!contained) {
                emitted.add(unit);
                emittedRanges.add(new Range(from, to));
            }
        }

        // 4. Join emitted contents with "\n"; return the result ("" if nothing emitted)
        return emitted.stream()
                .map(PxChunk::getContent)
                .collect(Collectors.joining("\n"));
    }

    private record Range(int from, int to) {
    }
}
