package de.spraener.prjxp.gldrtrvr.reader;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.reader.FileViewProvider;
import de.spraener.prjxp.common.reader.ReaderSupport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

// Reassembles a Markdown document (raw, or PDF/Word/HTML/RTF converted to Markdown) from its
// combined units: sections in document order, paragraphs within a section in NN order,
// parts already unsplit by the service before render.
@Component
public class MarkdownFileViewProvider implements FileViewProvider {

    public static final String MIME = "text/markdown";

    private static final String TYPE_FILE = "FILE";
    private static final String TYPE_SECTION = "SECTION";
    private static final String META_TYPE = "pxchunk_type";
    private static final String META_SECTION_NUMBER = "section_number";
    private static final int MAX_HEADER_LEVEL = 10;

    @Override
    public String mimeType() {
        return MIME;
    }

    @Override
    public String language() {
        return "markdown";
    }

    @Override
    public String render(List<PxChunk> units) {
        List<PxChunk> all = units == null ? List.of() : units;
        Set<String> fileIds = all.stream()
                .filter(u -> TYPE_FILE.equals(type(u)))
                .map(PxChunk::getId)
                .collect(Collectors.toSet());

        List<PxChunk> sections = all.stream()
                .filter(u -> isSection(u, fileIds))
                .sorted(Comparator.comparing(this::sectionNumber).thenComparing(this::id))
                .toList();

        if (sections.isEmpty()) {
            return finish(joinAllUnits(all));
        }

        List<PxChunk> paragraphs = all.stream()
                .filter(u -> !isSection(u, fileIds) && nonBlank(u.getContent()))
                .toList();

        Map<String, List<PxChunk>> byParent = new LinkedHashMap<>();
        for (PxChunk paragraph : paragraphs) {
            String parent = paragraph.getParent() == null ? "" : paragraph.getParent();
            byParent.computeIfAbsent(parent, key -> new ArrayList<>()).add(paragraph);
        }

        Set<String> sectionIds = sections.stream().map(PxChunk::getId).collect(Collectors.toSet());
        List<Block> blocks = new ArrayList<>();
        for (PxChunk section : sections) {
            String header = firstLine(section.getContent());
            List<PxChunk> group = byParent.getOrDefault(section.getId(), List.of());
            blocks.add(new Block(sectionNumber(section), section.getId(), block(header, group)));
        }
        for (Map.Entry<String, List<PxChunk>> entry : byParent.entrySet()) {
            if (sectionIds.contains(entry.getKey())) {
                continue;
            }
            String header = hierarchyHeader(firstParagraph(entry.getValue()), entry.getKey());
            blocks.add(new Block(orphanNumber(entry.getKey(), entry.getValue()), entry.getKey(), block(header, entry.getValue())));
        }
        blocks.sort(Comparator.comparing(Block::sectionNumber).thenComparing(Block::key));

        String joined = blocks.stream()
                .map(Block::text)
                .filter(text -> !text.isEmpty())
                .collect(Collectors.joining("\n\n"));
        return finish(joined);
    }

    // flat document: no SECTION units, join all non-blank units in fromLine order
    private String joinAllUnits(List<PxChunk> units) {
        return units.stream()
                .filter(u -> nonBlank(u.getContent()))
                .sorted(Comparator
                        .comparingInt((PxChunk u) -> ReaderSupport.parseLine(u.getFromLine()))
                        .thenComparing(PxChunk::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(PxChunk::getContent)
                .collect(Collectors.joining(""));
    }

    private String block(String header, List<PxChunk> paragraphs) {
        String body = joinParagraphs(paragraphs);
        String text = body.isEmpty() ? "## " + header : "## " + header + "\n\n" + body;
        return text.stripTrailing();
    }

    private String joinParagraphs(List<PxChunk> paragraphs) {
        List<String> parts = paragraphs.stream()
                .sorted(Comparator.comparingInt(this::paragraphIndex))
                .map(u -> trimmed(u.getContent()))
                .filter(part -> !part.isEmpty())
                .toList();
        return String.join("\n\n", parts);
    }

    private String trimmed(String content) {
        if (content == null) {
            return "";
        }
        String[] lines = content.split("\n", -1);
        int end = lines.length;
        while (end > 0 && lines[end - 1].isBlank()) {
            end--;
        }
        return String.join("\n", Arrays.copyOfRange(lines, 0, end)).strip();
    }

    private String firstLine(String content) {
        if (content == null) {
            return "";
        }
        for (String line : content.split("\n", -1)) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return content.strip();
    }

    // the NN suffix of the paragraph id; 0 on NumberFormat
    private int paragraphIndex(PxChunk unit) {
        String id = unit.getId();
        if (id == null) {
            return 0;
        }
        int index = id.lastIndexOf(':');
        if (index < 0) {
            return 0;
        }
        return ReaderSupport.parseLine(id.substring(index + 1));
    }

    private boolean isSection(PxChunk unit, Set<String> fileIds) {
        if (TYPE_SECTION.equals(type(unit))) {
            return true;
        }
        return !nonBlank(unit.getContent()) && unit.getParent() != null && fileIds.contains(unit.getParent());
    }

    private String sectionNumber(PxChunk unit) {
        String number = metadata(unit).get(META_SECTION_NUMBER);
        if (number != null && !number.isBlank()) {
            return number;
        }
        return numberAfterLastColon(unit.getId());
    }

    private String orphanNumber(String parent, List<PxChunk> paragraphs) {
        for (PxChunk paragraph : paragraphs) {
            String number = metadata(paragraph).get(META_SECTION_NUMBER);
            if (number != null && !number.isBlank()) {
                return number;
            }
        }
        return numberAfterLastColon(parent);
    }

    private String numberAfterLastColon(String key) {
        if (key == null) {
            return "";
        }
        int index = key.lastIndexOf(':');
        return index < 0 ? key : key.substring(index + 1);
    }

    // first present hN metadata of the first paragraph, falling back to the raw parent key
    private String hierarchyHeader(PxChunk paragraph, String parentKey) {
        for (int level = 1; level <= MAX_HEADER_LEVEL; level++) {
            String header = metadata(paragraph).get("h" + level);
            if (header != null && !header.isBlank()) {
                return header.strip();
            }
        }
        return parentKey;
    }

    private PxChunk firstParagraph(List<PxChunk> paragraphs) {
        return paragraphs.stream()
                .min(Comparator.comparingInt(this::paragraphIndex))
                .orElse(paragraphs.get(0));
    }

    private String type(PxChunk unit) {
        return metadata(unit).get(META_TYPE);
    }

    private Map<String, String> metadata(PxChunk unit) {
        return unit.getMetadata() == null ? Map.of() : unit.getMetadata();
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String id(PxChunk unit) {
        return unit.getId() == null ? "" : unit.getId();
    }

    private String finish(String text) {
        String trimmed = text == null ? "" : text.stripTrailing();
        return trimmed.isEmpty() ? "" : trimmed + "\n";
    }

    private record Block(String sectionNumber, String key, String text) {
    }
}
