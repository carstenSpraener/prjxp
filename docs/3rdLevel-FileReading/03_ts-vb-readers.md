# Phase 03: golden-retriever — TypeScript + Visual Basic Readers

> **STRICT EXECUTION RULES FOR THE AGENT:**
> 1. DO NOT run repository scans (`find_files`, `grep`, `search_code`, `list_dir`).
> 2. Read ONLY the files explicitly listed in the "File Scope" section below.
> 3. All required interfaces/DTOs are provided inline in this document. Do not fetch them from the codebase.
> 4. **MANDATORY FINAL STEP:** As soon as the implementation and tests (>= 80% coverage) are green, execute `/compact` IMMEDIATELY to clean up the context window before returning control.

## 1. Target & Scope

* **Objective:** Two `@Component` readers (`TypeScriptFileViewProvider`, `VisualBasicFileViewProvider`) that render the full source view from index units — same contract as the Java reader (Phase 02), adapted to each chunker's unit structure.
* **Target Files to Create:**
    * Create: `golden-retriever/src/main/java/de/spraener/prjxp/gldrtrvr/reader/TypeScriptFileViewProvider.java`
    * Create: `golden-retriever/src/main/java/de/spraener/prjxp/gldrtrvr/reader/VisualBasicFileViewProvider.java`
    * Create: `golden-retriever/src/test/java/de/spraener/prjxp/gldrtrvr/reader/TypeScriptFileViewProviderTest.java`
    * Create: `golden-retriever/src/test/java/de/spraener/prjxp/gldrtrvr/reader/VisualBasicFileViewProviderTest.java`
* **NO modifications, NO new dependencies.** Package `de.spraener.prjxp.gldrtrvr.reader` already exists (Phase 02).

## 2. Inline Required Context (Contracts & Signatures)

```java
// de.spraener.prjxp.common.reader (Phase 01):
public interface FileViewProvider { String mimeType(); String language(); String render(List<PxChunk> units); }
// ReaderSupport.parseLine(String) -> int (0 on null/blank/NumberFormat).
// PxChunk: @Data — getId/getParent/getFromLine/getContent/getMetadata. Content of method/doc units ends with '\n'.
```

### TypeScript units (TypeScriptCodeChunker)

- mime `text/x-typescript-code`, section key **`typescript_code_section`**, values `imports` / `methodDoc` / `method` / `classFrame`.
- **classFrame**: `id = <module>.<ClassName>`, `parent = null`. Content = import lines + class declaration line + class JSDoc (if the line above the class starts with `*`) + members: property lines verbatim (4-space indent), method signature lines = **original line with all `{` removed, trimmed, `;` appended** (4-space indent) + final `}`.
- **class method**: `id = <module>.<Class>.<name>`, `parent = <module>.<Class>`. Content = (when the method has NO jsdoc: first line `\t// Method <name> in class <Class>:` — a **synthetic prefix line**) + real source lines of the method.
- **methodDoc** (jsdoc): `id = <methodId>.jsdoc`, `parent = <methodId>`. Content = **also prefixed** with the same synthetic `\t// Method ...` line, then the real jsdoc lines.
- **top-level function**: `id = <module>.<fnName>`, `parent = <module>` (no synthetic prefix). Its jsdoc: `id + ".jsdoc"`, same parent.
- **imports**: `id = <module>.imports`, `parent = <module>` — redundant (inside frame content); do not render.

### Visual Basic units (VisualBasicCodeChunker)

- mime `text/x-visual-basic-code`, section key **`visualbasic_code_section`**, values `imports` / `methodDoc` / `method` / `classFrame`.
- **classFrame**: `id = <module>.<Container>` (or `<module>` without container), `parent = null`. Content = **real file lines**: header/imports lines + container declaration + all non-member lines kept verbatim, each **member body collapsed to its declaration line** (`codeLines.get(member.startLine)`) + closing `End ...` line.
- **method**: `id = <parentId>.<memberName>` (overloads: `...<name>.overloadN`), `parent = <parentId>`. Content = real member lines (declaration first, `End Sub`/`End Function`/`End Property` last) + trailing `'\n'`.
- **methodDoc**: `id = <memberId>.doc`, `parent = <memberId>`. **The doc comment lines are NOT part of the member body — they remain verbatim inside the frame** (the frame keeps all non-member lines). Never splice docs.
- **header/imports**: `id = <module>.header` / `<module>.imports`, `parent = <module>` — already inside the frame; do not render.

## 3. Implementation (exact)

### 3.1 `TypeScriptFileViewProvider`

```java
@Component
public class TypeScriptFileViewProvider implements FileViewProvider {
    public static final String MIME = "text/x-typescript-code";
    private static final String SECTION_KEY = "typescript_code_section";
    private static final Pattern SYNTHETIC_PREFIX = Pattern.compile("^\\t// Method \\w+ in class \\w+:$");

    mimeType() -> MIME;  language() -> "typescript";

    render(units) {
        frames  = section classFrame, sorted by parseLine(fromLine)
        methods = section method,    sorted by parseLine(fromLine)
        docs    = map id -> unit,    section methodDoc
        if (frames empty) return joinAllUnits(units);                       // same degraded fallback as Java reader
        Set<String> placed = new HashSet<>();
        String result = "";
        for (frame : frames) result += renderFrame(frame, methods, docs, placed);
        for (m : methods where !placed.contains(m.getId()))                  // top-level functions (parent == module, no frame)
            result += "## " + m.getId() + "\n\n```typescript\n" + unitText(docs, m) + "```\n";
        return result;
    }

    renderFrame(frame, methods, docs, placed) {
        List<String> lines = new ArrayList<>(Arrays.asList(frame.getContent().split("\n", -1)));
        Set<Integer> usedLines = new HashSet<>();
        List<SplicePlan> plans = new ArrayList<>();   // (int line, List<String> replacement); line -1 = no target
        for (m : methods where frame.getId().equals(m.getParent()) || idPrefixMatch(m, frame.getId())) {
            placed.add(m.getId());
            String body = stripPrefix(m.getContent());
            PxChunk doc = docs.get(m.getId() + ".jsdoc");
            String docText = doc != null ? stripPrefix(doc.getContent()) : null;
            String bodyFirst = firstLine(body);
            int idx = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (!usedLines.contains(i) && normalizeForMatch(lines.get(i)).equals(normalizeForMatch(bodyFirst))) { idx = i; break; }
            }
            if (idx >= 0) usedLines.add(idx);
            List<String> replacement = new ArrayList<>();
            if (docText != null && !docText.isBlank()) replacement.addAll(split(docText));
            replacement.addAll(split(body));
            plans.add(new SplicePlan(idx, replacement));
        }
        // apply in DESCENDING index order so earlier indices stay valid (overload safety);
        // plans with line -1 insert before lastBraceIndex(lines) (or append at end)
        return "\n## " + frame.getId() + "\n\n```typescript\n" + String.join("\n", lines) + "\n```\n";
    }

    // "    public foo(bar: string): void;"  vs  "    public foo(bar: string): void {"  ->  equal
    static String normalizeForMatch(String line) {
        String s = line.strip();
        if (s.endsWith(";") || s.endsWith("{")) s = s.substring(0, s.length() - 1);
        return s.strip().replaceAll("\\s+", " ");
    }
    static String stripPrefix(String content) {
        String[] l = content.split("\n", -1);
        if (l.length > 1 && SYNTHETIC_PREFIX.matcher(l[0]).matches())
            return String.join("\n", Arrays.copyOfRange(l, 1, l.length));
        return content;
    }
    // idPrefixMatch: m.getId().length() > frame.getId().length()+1 && m.getId().startsWith(frame.getId() + ".")
    // lastBraceIndex: index of last line whose stripped form ends with "}" (or -1)
    // unitText(docs, m): docText + body, each stripPrefix-ed; used for orphan top-level functions
    // joinAllUnits: fromLine-sorted unit contents joined by "\n" (same as Java reader fallback)
}
```

### 3.2 `VisualBasicFileViewProvider`

```java
@Component
public class VisualBasicFileViewProvider implements FileViewProvider {
    public static final String MIME = "text/x-visual-basic-code";
    private static final String SECTION_KEY = "visualbasic_code_section";

    mimeType() -> MIME;  language() -> "visualbasic";

    render(units) {
        frames  = section classFrame, sorted by parseLine(fromLine)
        methods = section method,    sorted by parseLine(fromLine)
        if (frames empty) return joinAllUnits(units);
        Set<String> placed = new HashSet<>();
        String result = "";
        for (frame : frames) {
            List<String> lines = new ArrayList<>(Arrays.asList(frame.getContent().split("\n", -1)));
            Set<Integer> usedLines = new HashSet<>();
            List<SplicePlan> plans = new ArrayList<>();   // same shape as the TS provider
            for (m : methods where frame.getId().equals(m.getParent()) || idPrefixMatch(m, frame.getId())) {
                placed.add(m.getId());
                String body = m.getContent();                      // NO prefix stripping — VB bodies are pure source
                String bodyFirst = firstLine(body);
                int idx = -1;                                      // frame keeps the declaration line VERBATIM -> exact match
                for (int i = 0; i < lines.size(); i++)
                    if (!usedLines.contains(i) && lines.get(i).strip().equals(bodyFirst.strip())) { idx = i; break; }
                if (idx >= 0) usedLines.add(idx);
                plans.add(new SplicePlan(idx, Arrays.asList(body.split("\n", -1))));
            }
            // apply in DESCENDING index order; plans with line -1 insert before lastEndIndex(lines) (or append at end)
            // NOTE: methodDoc units are intentionally NOT spliced — comment lines remain verbatim in the frame.
            result += "\n## " + frame.getId() + "\n\n```vb\n" + String.join("\n", lines) + "\n```\n";
        }
        for (m : methods where !placed.contains(m.getId()))
            result += "## " + m.getId() + "\n\n```vb\n" + m.getContent() + "```\n";
        return result;
    }
    // lastEndIndex: index of the last line matching "^(?i)\\s*End\\s+(Sub|Function|Property|Class|Module|Interface|Structure)\\b" else -1
}
```

Both classes share the same `idPrefixMatch` / `joinAllUnits` / `firstLine` helper shapes as the Java reader (re-implement privately in each class — do NOT create a shared base class).

## 4. TDD — Write These Tests FIRST (>= 80% LOC coverage per class)

JUnit 5 + AssertJ; helper builds units via `PxChunk.create(...)` with mime/id/parent/section/content.

`TypeScriptFileViewProviderTest` — frame fixture:
```
import { x } from './x';

export class Foo {
    public count: number;
    public inc(a: number): number;
}
```
1. `splicesMethodBodyReplacingSemicolonSignature` — method unit `mod.Foo.inc`, body `\t// Method inc in class Foo:\n    public inc(a: number): number {\n        return a + 1;\n    }\n` → `return a + 1;` present once; the `;`-signature line is gone; the synthetic prefix line `\t// Method ...` is NOT in the output.
2. `insertsJsDocAboveBodyAndStripsItsPrefix` — method WITHOUT prefix (has doc) + doc unit `mod.Foo.inc.jsdoc` content `\t// Method inc in class Foo:\n    /** Increments. */\n` → `/** Increments. */` directly above the body; no `// Method` line.
3. `topLevelFunctionRenderedAsOrphanBlock` — function unit `mod.helper` (parent `mod`, no matching frame) + its jsdoc → `## mod.helper` + ```typescript block, jsdoc then body.
4. `multiLineDeclarationMatches` — body first line `    public multi(a: number,\n    ...` is multi-line: matching uses the FIRST body line only; a frame line `    public multi(a: number,` matches after normalization.
5. `fallbackWhenNoFrame` / `mimeAndLanguage` — as in the Java phase.

`VisualBasicFileViewProviderTest` — frame fixture:
```
Imports System

Public Class Foo
    Private count As Integer
    Public Sub Inc(ByVal a As Integer)
    Public Function GetCount() As Integer
End Class
```
6. `splicesSubBodyAtDeclarationLine` — method `Foo.Inc`, body `    Public Sub Inc(ByVal a As Integer)\n        count = count + a\n    End Sub\n` → `count = count + a` present once, `End Sub` present exactly once.
7. `overloadIdsBothSpliced` — `Inc.overload1` + `Inc.overload2` both parent `Foo`; two identical declaration lines in the frame → each gets its distinct body (first body to first line, second body to second line — match sequentially, skip already-used indices).
8. `docCommentsNotDuplicated` — doc unit `Foo.Inc.doc` (`' Increments count.\n`) exists AND the frame already contains `' Increments count.` above the declaration → the comment occurs exactly once in the output.
9. `fallbackInsertsBeforeEndLine` — frame without the declaration line → body inserted before the `End Class` line.
10. `noFrameFallsBackToJoin / mimeAndLanguage` — as in the Java phase.

## 5. Definition of Done

- All 4 files created; no other file touched; both providers are Spring `@Component`s.
- `./gradlew :golden-retriever:test` green with all 10 tests; ≥ 80% LOC coverage of both new classes (every splice branch: match / no-match / prefix-strip / no-doc / orphan).
- `./gradlew build` green.
- Execute `/compact`, then return.
