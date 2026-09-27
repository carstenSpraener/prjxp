# Phase 04: golden-retriever — MarkdownFileViewProvider

> **STRICT EXECUTION RULES FOR THE AGENT:**
> 1. DO NOT run repository scans (`find_files`, `grep`, `search_code`, `list_dir`).
> 2. Read ONLY the files explicitly listed in the "File Scope" section below.
> 3. All required interfaces/DTOs are provided inline in this document. Do not fetch them from the codebase.
> 4. **MANDATORY FINAL STEP:** As soon as the implementation and tests (>= 80% coverage) are green, execute `/compact` IMMEDIATELY to clean up the context window before returning control.

## 1. Target & Scope

* **Objective:** A `@Component` reader that reassembles a document (Markdown, or PDF/Word/HTML/RTF converted to Markdown) from its paragraph units — sections in document order, paragraphs within each section, parts unsplit.
* **Target Files to Create:**
    * Create: `golden-retriever/src/main/java/de/spraener/prjxp/gldrtrvr/reader/MarkdownFileViewProvider.java`
    * Create: `golden-retriever/src/test/java/de/spraener/prjxp/gldrtrvr/reader/MarkdownFileViewProviderTest.java`
* **NO modifications, NO new dependencies.** Package `de.spraener.prjxp.gldrtrvr.reader` already exists (Phases 02–03).

## 2. Inline Required Context (Contracts & Signatures)

```java
// de.spraener.prjxp.common.reader (Phase 01):
public interface FileViewProvider { String mimeType(); String language(); String render(List<PxChunk> units); }
// ReaderSupport.parseLine(String) -> int (0 on null/blank/NumberFormat).
// PxChunk: @Data — getId/getParent/getContent/getMetadata (Map<String,String>).
```

### Markdown unit structure (MarkdownChunker — facts you may rely on)

All units of one file share `mimeType = "text/markdown"` and `file`. Three shapes:

| Shape | id | parent | content | metadata |
|---|---|---|---|---|
| FILE placeholder | `<fileName>` | null | `""` (always empty) | `pxchunk_type = "FILE"` |
| SECTION | `<fileName>:<sectionNumber>` | `<fileName>` | the deepest header text, e.g. `1.2.3 Titel` (no `#`) | `pxchunk_type = "SECTION"`, `section_number = <sectionNumber>` |
| paragraph | `<fileName>:<sectionNumber>:<NN>` (NN = 0-padded 2-digit paragraph counter) | `<fileName>:<sectionNumber>` | real Markdown lines, each + `'\n'` (may be split into parts; combined by the service before `render`) | `section_number`, `hierarchy = "H1 > H2 > H3"`, `h1`…`hN`, `type = "md_paragraph"` |

`sectionNumber` is dot-separated, zero-padded per level (`01`, `01.02`, `01.02.03`) — **string comparison preserves document order** (a parent section sorts before its children because it is a prefix).

## 3. Implementation (exact)

```java
@Component
public class MarkdownFileViewProvider implements FileViewProvider {
    public static final String MIME = "text/markdown";

    mimeType() -> MIME;  language() -> "markdown";

    render(List<PxChunk> units) {
        // 1. Paragraphs: non-blank content, NOT a SECTION unit
        //    (a paragraph is any unit whose content is non-blank; FILE units are blank and SECTION units carry only a header)
        // 2. Group paragraphs by getParent() (the section id); within a group sort by the paragraph index:
        //    idx = Integer.parseInt(unit.getId().substring(unit.getId().lastIndexOf(':') + 1))   // the NN suffix; 0 on NumberFormat
        // 3. Sections: units with metadata pxchunk_type == "SECTION" (or, defensively, blank-content units
        //    whose parent equals a known FILE id); sort by metadata "section_number" ASC (string compare), then id.
        // 4. Render, section by section in document order:
        //        "## " + section.getContent().strip() + "\n\n"
        //        + concatenation of that section's paragraphs (in idx order), each trimmed of trailing blank lines
        //    Sections that have no paragraph units still emit their header line.
        //    Paragraphs whose parent section unit is MISSING from `units` are not lost: collect their distinct
        //    parent keys, sort them like sections, and emit a synthetic header "## " + (hierarchy metadata, else the parent key) for them.
        // 5. If there are no sections at all (flat document): join all non-blank units in fromLine order.
        // 6. Join section blocks with a single blank line; ensure the result ends with exactly one '\n'. Never return null.
    }
}
```

## 4. TDD — Write These Tests FIRST (>= 80% LOC coverage)

JUnit 5 + AssertJ. Helper builds units via `PxChunk.create(...)` setting mime/file/id/parent/content/metadata as tabulated above.

Fixture: file `doc.md`, sections `01` (header `Einführung`), `01.02` (header `1.2 Unterpunkt`), `02` (header `Fazit`); plus a FILE placeholder unit (empty content).

1. `reassemblesSectionsInDocumentOrder` — paragraphs `doc.md:01:01`, `doc.md:01:02`, `doc.md:01.02:01`, `doc.md:02:01` in scrambled input order → output order: header `Einführung` + its 2 paragraphs, then `1.2 Unterpunkt` + its paragraph, then `Fazit` + its paragraph; assert with `indexOf` comparisons.
2. `zeroPaddedSectionNumbersSortCorrectly` — sections `01.02` and `01.10` (both with 1 paragraph each) → `01.02` block before `01.10` block (string compare of `section_number`).
3. `skipsEmptyFileUnitAndEmitsHeaderOnlySections` — FILE unit (empty) absent from output; a section with zero paragraphs emits exactly `## <header>\n`.
4. `paragraphWithoutSectionUnitKeepsHierarchyHeader` — paragraph `doc.md:03:01` with metadata `h1 = "3 Kap"` whose section unit is missing → synthetic `## 3 Kap` (from `h1`… use the FIRST present `hN` metadata, falling back to the raw parent key) precedes the paragraph.
5. `paragraphIndexOrderWithinSection` — paragraphs with NN `02`, `01`, `10` (deliberately scrambled) → output order 01, 02, 10.
6. `flatDocumentFallsBackToLineOrder` — only non-blank units, no SECTION units → joined in fromLine order.
7. `mimeAndLanguage` — assert `"text/markdown"`.

## 5. Definition of Done

- Both files created; no other file touched; provider is a Spring `@Component`.
- `./gradlew :golden-retriever:test` green with all 7 tests; ≥ 80% LOC coverage of `MarkdownFileViewProvider` (branches: section order, missing-section recovery, flat fallback, NN parse failure).
- `./gradlew build` green.
- Execute `/compact`, then return.
