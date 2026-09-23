package de.spraener.prjxp.common.reader;

import static org.assertj.core.api.Assertions.assertThat;

import de.spraener.prjxp.common.model.PxChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReaderSupportTest {

    @Test
    void combineUnitsGroupsPartsAndUnsplits() {
        // unit "u2" presented first to prove the result is re-sorted by fromLine,
        // the two parts of "u1" share an id with overlap 5 (part2 starts with the
        // last 5 chars of part1), so PxChunk.combine unsplit yields 17 chars.
        PxChunk unit2 = part("u2", 0, 1, "solo", "20", "25");
        PxChunk part1 = part("u1", 0, 2, "ABCDEFGHIJ", "1", "10");
        PxChunk part2 = part("u1", 1, 2, "FGHIJKLMNOPQ", null, null);

        List<PxChunk> units = ReaderSupport.combineUnits(List.of(unit2, part1, part2));

        assertThat(units).hasSize(2);
        assertThat(units).extracting(PxChunk::getId).containsExactly("u1", "u2");
        assertThat(units.get(0).getContent()).isEqualTo("ABCDEFGHIJKLMNOPQ");
        assertThat(units.get(1).getContent()).isEqualTo("solo");
    }

    @Test
    void normalizePathStripsSeparators() {
        assertThat(ReaderSupport.normalizePath("\\src\\Foo.java")).isEqualTo("src/Foo.java");
        assertThat(ReaderSupport.normalizePath("/src/Foo.java")).isEqualTo("src/Foo.java");
        assertThat(ReaderSupport.normalizePath(null)).isNull();
        assertThat(ReaderSupport.normalizePath("src/Foo.java/")).isEqualTo("src/Foo.java");
        assertThat(ReaderSupport.normalizePath("")).isEmpty();
    }

    @Test
    void parseLine() {
        assertThat(ReaderSupport.parseLine("12")).isEqualTo(12);
        assertThat(ReaderSupport.parseLine(" 7 ")).isEqualTo(7);
        assertThat(ReaderSupport.parseLine(null)).isZero();
        assertThat(ReaderSupport.parseLine("x")).isZero();
        assertThat(ReaderSupport.parseLine("")).isZero();
    }

    private PxChunk part(String id, int part, int total, String content, String fromLine, String toLine) {
        return PxChunk.create(
                chunk -> chunk.setId(id),
                chunk -> chunk.setPart(part),
                chunk -> chunk.setTotal(total),
                chunk -> chunk.setSize(10),
                chunk -> chunk.setOverlap(5),
                chunk -> chunk.setFromLine(fromLine),
                chunk -> chunk.setToLine(toLine),
                chunk -> chunk.setContent(content));
    }
}
