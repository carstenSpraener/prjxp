# Phase 01: prjxp-common — Reader Contracts, SPI, Generic Fallback

> **STRICT EXECUTION RULES FOR THE AGENT:**
> 1. DO NOT run repository scans (`find_files`, `grep`, `search_code`, `list_dir`).
> 2. Read ONLY the files explicitly listed in the "File Scope" section below.
> 3. All required interfaces/DTOs are provided inline in this document. Do not fetch them from the codebase.
> 4. **MANDATORY FINAL STEP:** As soon as the implementation and tests (>= 80% coverage) are green, execute `/compact` IMMEDIATELY to clean up the context window before returning control.

## 1. Target & Scope

* **Objective:** Create the shared reader contracts in `prjxp-common`: three JSON records, the `FileViewProvider` SPI, the language-agnostic `GenericFileViewProvider`, and the `ReaderSupport` utility — plus tests.
* **Target Files to Create:**
    * Create: `prjxp-common/src/main/java/de/spraener/prjxp/common/model/FileView.java`
    * Create: `prjxp-common/src/main/java/de/spraener/prjxp/common/model/MethodView.java`
    * Create: `prjxp-common/src/main/java/de/spraener/prjxp/common/model/SymbolReadResult.java`
    * Create: `prjxp-common/src/main/java/de/spraener/prjxp/common/reader/FileViewProvider.java`
    * Create: `prjxp-common/src/main/java/de/spraener/prjxp/common/reader/GenericFileViewProvider.java`
    * Create: `prjxp-common/src/main/java/de/spraener/prjxp/common/reader/ReaderSupport.java`
    * Create: `prjxp-common/src/test/java/de/spraener/prjxp/common/reader/GenericFileViewProviderTest.java`
    * Create: `prjxp-common/src/test/java/de/spraener/prjxp/common/reader/ReaderSupportTest.java`
    * Create: `prjxp-common/src/test/java/de/spraener/prjxp/common/model/FileViewTest.java`
* **NO files to modify. NO new dependencies. NO Spring annotations in this module (plain classes/records only).**

## 2. Inline Required Context (Contracts & Signatures)

Do not search the codebase for these. Use these exact definitions:

```java
// de.spraener.prjxp.common.model.PxChunk — existing, as-is. @Data class (Lombok):
//   String id; String mimeType; String file; String parent;
//   int part; int total; String fromLine; String toLine;   // line numbers are STRINGS
//   int size; int overlap; String embeddingPrefix;
//   Map<String, String> metadata = new HashMap<>();  String content;
// Constants (public static final String):
//   PXCHUNK_ID="pxchunk_id", PXCHUNK_MIME_TYPE="pxchunk_mimeType", PXCHUNK_FILE="pxchunk_file",
//   PXCHUNK_PARENT="pxchunk_parent", PXCHUNK_PART="pxchunk_part", PXCHUNK_TOTAL="pxchunk_total",
//   PXCHUNK_FROM_LINE="pxchunk_fromLine", PXCHUNK_TO_LINE="pxchunk_toLine", ...
// Key statics:
//   static PxChunk combine(List<PxChunk> parts)
//     // null/empty -> null; sorts by part; copies root's fields; content = ContentSplitter(root.size, root.overlap).unsplit(list)
//   static PxChunk create(Consumer<PxChunk>... modifiers)   // public factory, used in tests

// de.spraener.prjxp.common.util.ContentSplitter — existing:
//   new ContentSplitter(int chunkSize, int overlap)
//   String unsplit(List<PxChunk> chunks)  // 1st part appended verbatim; each later part: content.substring(overlap)
```

## 3. Implementation (exact)

### 3.1 `FileView` (record, model package)

```java
public record FileView(
        String file,            // normalized path as found in the index
        String language,        // detected language, e.g. "java"
        int chunkCount,         // raw chunks (parts) the file was built from — reconstruction transparency
        String content,         // rendered source view (null on error)
        boolean truncated,      // output cap applied
        Integer totalLines,     // lines of the full view (null on error)
        Integer returnedLines,  // lines actually returned after offset/limit (null on error)
        List<String> candidates,// non-empty when the file path was ambiguous
        String error) {         // error message, null when OK

    public static FileView error(String requestedFile, String message) {
        return new FileView(requestedFile, null, 0, null, false, null, null, List.of(), message);
    }

    public static FileView ambiguous(String requestedFile, List<String> candidates) {
        return new FileView(requestedFile, null, 0, null, false, null, null, candidates,
                "Ambiguous file path — " + candidates.size() + " candidates");
    }
}
```

### 3.2 `MethodView` (record, model package)

```java
public record MethodView(
        String fqn,        // "com.example.Foo#bar" (symbol_fqn) or class FQN for class-level hits
        String file,
        Integer lineFrom,  // 1-based source lines of the method body (null when unknown)
        Integer lineTo,
        String javadoc,    // null when absent
        String body)       // complete implementation, original lines
{ }
```

### 3.3 `SymbolReadResult` (record, model package)

```java
public record SymbolReadResult(
        List<MethodView> matches,     // max 10 full matches
        List<String> remainingFqns,   // distinct fqns of matches beyond the 10
        String error) {               // null when OK
    public static SymbolReadResult error(String message) {
        return new SymbolReadResult(List.of(), List.of(), message);
    }
}
```

### 3.4 `FileViewProvider` (interface, package `de.spraener.prjxp.common.reader`)

```java
public interface FileViewProvider {
    /** MIME type this provider renders, e.g. "text/x-java-code". */
    String mimeType();
    /** Human language name for reporting, e.g. "java". */
    String language();
    /**
     * Renders the source view of one file from its COMBINED units (parts already reassembled via PxChunk.combine).
     * Units may be in any order; the provider sorts as needed. Never returns null.
     */
    String render(List<PxChunk> units);
}
```

### 3.5 `GenericFileViewProvider` (class, NOT a Spring bean — the registry instantiates it as fallback)

```java
public class GenericFileViewProvider implements FileViewProvider {
    @Override public String mimeType() { return "*/*"; }
    @Override public String language() { return "generic"; }

    @Override
    public String render(List<PxChunk> units) {
        // 1. Drop units with blank content (placeholder FILE/SECTION chunks, empty frames)
        // 2. Sort by fromLine ASC (ReaderSupport.parseLine, 0 when unparseable), then toLine DESC (wider first), then id
        // 3. Emit a unit only if its range [fromLine, toLine] is NOT contained in an already-emitted range
        //    (containment: from >= emitted.from && to <= emitted.to). This deduplicates nested units
        //    (e.g. methods inside a class frame) without a language-specific reader.
        // 4. Join emitted contents with "\n"; return the result ("" if nothing emitted)
    }
}
```

### 3.6 `ReaderSupport` (final class, static utils only)

```java
public final class ReaderSupport {
    /** Group raw chunks by id (LinkedHashMap, first-seen order), sort each group by part,
     *  PxChunk.combine each group; return units sorted by fromLine ASC, then id. */
    public static List<PxChunk> combineUnits(List<PxChunk> rawChunks) { ... }

    /** Trim, backslash->slash, strip all leading slashes, strip trailing slash. null -> null. */
    public static String normalizePath(String path) { ... }

    /** Integer.parseInt with trim; null/blank/NumberFormatException -> 0. */
    public static int parseLine(String value) { ... }
}
```

## 4. TDD — Write These Tests FIRST (>= 80% LOC coverage of new main classes)

Plain JUnit 5 + AssertJ. Build chunks with `PxChunk.create(...)`; for multi-part units set `part`/`total`/`content`/`overlap` consistently with `ContentSplitter` semantics (later parts start with `overlap` chars of the previous part) so `PxChunk.combine` unsplit works.

`GenericFileViewProviderTest`:
1. `reconstructsSequentialUnitsInLineOrder` — 3 disjoint units (lines 1–10, 11–20, 21–30) given in scrambled order → output = contents in fromLine order joined by "\n".
2. `skipsUnitsNestedInWiderUnit` — wide unit (lines 1–100) + nested unit (lines 5–10) → output contains the wide content exactly once, nested not duplicated.
3. `skipsBlankUnits` — one unit with `content=""` is ignored.
4. `handlesUnparseableLineNumbers` — fromLine null → treated as 0, no exception.

`ReaderSupportTest`:
5. `combineUnitsGroupsPartsAndUnsplits` — one unit with 2 parts sharing an id (content crafted with overlap 5) → 1 combined unit with the unsplit content; two distinct ids → 2 units sorted by fromLine.
6. `normalizePathStripsSeparators` — `"\\src\\Foo.java"` → `"src/Foo.java"`; `"/src/Foo.java"` → `"src/Foo.java"`; `null` → `null`; `"src/Foo.java/"` → `"src/Foo.java"`.
7. `parseLine` — `"12"` → 12; `" 7 "` → 7; `null`/`"x"`/`""` → 0.

`FileViewTest`:
8. `errorFactoryFillsDefaults` / `ambiguousFactoryCarriesCandidates` — assert all 9 components.

## 5. Definition of Done

- All 8 files created exactly as specified; no other file touched.
- `./gradlew :prjxp-common:test` green; ≥ 80% LOC coverage of `GenericFileViewProvider`, `ReaderSupport` (verify by reading your code; there are no uncovered branches left).
- `./gradlew build` green (nothing else depends on these new types yet).
- Execute `/compact`, then return.
