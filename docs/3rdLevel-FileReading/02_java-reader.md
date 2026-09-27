# Phase 02: golden-retriever — JavaFileViewProvider

> **STRICT EXECUTION RULES FOR THE AGENT:**
> 1. DO NOT run repository scans (`find_files`, `grep`, `search_code`, `list_dir`).
> 2. Read ONLY the files explicitly listed in the "File Scope" section below.
> 3. All required interfaces/DTOs are provided inline in this document. Do not fetch them from the codebase.
> 4. **MANDATORY FINAL STEP:** As soon as the implementation and tests (>= 80% coverage) are green, execute `/compact` IMMEDIATELY to clean up the context window before returning control.

## 1. Target & Scope

* **Objective:** A `@Component` reader that renders a complete Java source view from the index units of one file: per-class frame skeletons with all method bodies (and javadocs) spliced in — the "original" file structure, no relevance filtering, no budget.
* **Target Files to Create:**
    * Create: `golden-retriever/src/main/java/de/spraener/prjxp/gldrtrvr/reader/JavaFileViewProvider.java`
    * Create: `golden-retriever/src/test/java/de/spraener/prjxp/gldrtrvr/reader/JavaFileViewProviderTest.java`
* **NO modifications, NO new dependencies.** The `de.spraener.prjxp.gldrtrvr.reader` package is new.

## 2. Inline Required Context (Contracts & Signatures)

Do not search the codebase for these:

```java
// de.spraener.prjxp.common.reader.FileViewProvider (created in Phase 01):
public interface FileViewProvider {
    String mimeType();          // -> "text/x-java-code"
    String language();          // -> "java"
    String render(List<PxChunk> units);   // units = COMBINED units of one file (parts reassembled)
}
// de.spraener.prjxp.common.reader.ReaderSupport (Phase 01): static parseLine(String)->int (0 on failure).
// de.spraener.prjxp.common.model.PxChunk: @Data — getId/getParent/getFile/getFromLine/getToLine/
//   getContent/getMetadata (Map<String,String>). Lombok @Data getters.
```

### Java unit structure (written by JavaCodeChunker — facts you may rely on)

- mime `text/x-java-code`; section stored under metadata key **`java_code_section`** with values `imports` / `method` / `methodDoc` / `classFrame` / `dependenciesInfo` / `unknown`.
- **classFrame** (one per declared type, nested types included): `id = <typeFqn>`, `parent = null`. Content is a *synthesized skeleton*: package line + import lines + class javadoc + class-header lines up to (not incl.) the first `{` + each field + for each method: optional annotation line(s) (4-space indent) + `    <sig>` + finally `}`. **`<sig>` = `m.getDeclarationAsString(false, false, false)` — i.e. the method signature WITHOUT parameter types, e.g. `    public MClass createMClass()`.**
- **method**: `id = <typeFqn>.<sig>` (same sig string as above), `parent = <typeFqn>`. Content = the **real source lines** of the method (annotations first, then the declaration line **with** parameter types, then the body) — each line + `'\n'`, i.e. content ends with a newline.
- **methodDoc**: `id = <methodId>.javadoc`, `parent = <methodId>`. Content = real javadoc lines + `'\n'`.
- **imports**: `id = <typeFqn>.imports` — redundant for the view (already inside the frame content); must NOT be rendered again.

## 3. Implementation (exact algorithm)

```java
@Component
public class JavaFileViewProvider implements FileViewProvider {
    public static final String MIME = "text/x-java-code";
    private static final String SECTION_KEY = "java_code_section";

    mimeType() -> MIME;  language() -> "java";

    render(List<PxChunk> units) {
        frames  = units where metadata[SECTION_KEY] == "classFrame", sorted by parseLine(fromLine)
        methods = units where section == "method",  sorted by parseLine(fromLine)
        docs    = map: id -> unit,  for units where section == "methodDoc"

        if (frames is empty)
            return concatenation of ALL unit contents in fromLine order joined by "\n"   // degraded fallback

        String result = "";
        Set<String> placed = new HashSet<>();
        for (frame : frames) {
            result += renderFrame(frame, methods, docs, placed);
        }
        // orphan methods (parent FQN has no frame unit, e.g. frame was dropped from the index):
        for (m : methods where !placed.contains(m.getId()))
            result += "## " + m.getId() + "\n\n```java\n" + (doc(m)?.content != null ? doc(m).content : "") + m.getContent() + "```\n";
        return result;
    }

    String renderFrame(PxChunk frame, List<PxChunk> methods, Map<String,PxChunk> docs, Set<String> placed) {
        List<String> lines = new ArrayList<>(Arrays.asList(frame.getContent().split("\n", -1)));
        List<SplicePlan> plans = new ArrayList<>();
        for (m : methods where frame.getId().equals(m.getParent())
                               || (m.getId().length() > frame.getId().length() + 1
                                   && m.getId().startsWith(frame.getId() + "."))) {   // parent first, id-prefix fallback
            placed.add(m.getId());
            String sig = m.getId().substring(frame.getId().length() + 1);            // e.g. "public MClass createMClass()"
            plans.add(planSig(lines, usedLines, sig, docs.get(m.getId() + ".javadoc"), m.getContent()));
        }
        applyInDescendingIndexOrder(lines, plans);   // splice highest target index first so lower indices stay valid
        return "\n## " + frame.getId() + "\n\n```java\n" + String.join("\n", lines) + "\n```\n";
    }

    // SplicePlan: record of (int topLine, int sigLine, List<String> replacement) — indices into the ORIGINAL lines list;
    //             topLine <= sigLine; both -1 when no target was found.
    SplicePlan planSig(List<String> lines, Set<Integer> usedLines, String sig, PxChunk doc, String bodyContent) {
        String sigNorm = sig.strip();
        int idx = -1;
        for (int i = 0; i < lines.size(); i++)
            if (!usedLines.contains(i) && lines.get(i).strip().equals(sigNorm)) { idx = i; break; }
        if (idx < 0) return new SplicePlan(-1, -1, replacementOf(doc, bodyContent));  // -> fallback insertion (see apply)
        int top = idx;
        while (top > 0 && lines.get(top - 1).strip().startsWith("@")) top--;           // annotation lines above the sig
        usedLines.addAll(range(top, idx));                                              // reserve the whole block
        return new SplicePlan(top, idx, replacementOf(doc, bodyContent));
    }

    void applyInDescendingIndexOrder(List<String> lines, List<SplicePlan> plans) {
        plans.sort(Comparator.comparingInt(SplicePlan::sigLine).reversed());
        for (SplicePlan p : plans) {
            if (p.sigLine() < 0) {
                // no target found: insert before the LAST "}" line (or append at end if none)
                int close = -1;
                for (int i = lines.size() - 1; i >= 0; i--)
                    if (lines.get(i).strip().equals("}")) { close = i; break; }
                lines.addAll(close >= 0 ? close : lines.size(), p.replacement());
            } else {
                lines.subList(p.topLine(), p.sigLine() + 1).clear();
                lines.addAll(p.topLine(), p.replacement());
            }
        }
    }

    // replacement = (doc != null && doc content non-blank ? doc lines : []) + body lines, via split("\n", -1)
    List<String> replacementOf(PxChunk doc, String bodyContent) { ... }
}
```

Notes:
- `split("\n", -1)` keeps the trailing empty element (content ends with `'\n'`); it renders as a blank line — correct.
- Two-pass (plan, then apply descending) makes **overloads with identical signature lines** safe: each gets a distinct skeleton line, later splices never invalidate earlier indices.
- Do NOT use the vectorSearch `JavaPromptModifier` string-replace mechanics (it duplicates the return-type prefix). The line-replacement above is cleaner and equally robust because the skeleton line and the sig string come from the same AST call.

## 4. TDD — Write These Tests FIRST (>= 80% LOC coverage)

Plain JUnit 5 + AssertJ. Helper in the test builds units via `PxChunk.create(...)` with mimeType, file, id, parent, section metadata, content. All single-part in this phase (multi-part is covered by `PxChunk.combine`, not by this class).

Frame content fixture (2 methods, one with annotation + javadoc):
```
package de.example;

import java.util.List;

public class Foo {
    private int x;

    public void bar(int a)
    @Override
    public String qux()
}
```
1. `splicesMethodBodyAtSignatureLine` — frame + method unit `de.example.Foo.public void bar(int a)` with body `    public void bar(int a) {\n        x = a;\n    }\n` → rendered view contains the full body at the signature position; the skeleton sig line `    public void bar(int a)` is gone; `x = a;` present exactly once.
2. `insertsJavadocAboveBodyAndDropsDuplicateAnnotation` — method `qux` with body starting `    @Override\n    public String qux() {\n...` + doc unit `...qux().javadoc` `/** Does qux. */\n` → output order: `/** Does qux. */` then `    @Override` (from body) then body; the skeleton's `    @Override` line above the sig is NOT duplicated; `@Override` occurs exactly once in the view.
3. `rendersMultipleFramesInOrder` — two frames (`de.example.Foo`, `de.example.Bar`) → both `## de.example.Foo` / `## de.example.Bar` blocks, Foo before Bar when fromLine lower.
4. `orphanMethodRenderedAsPlainBlock` — method whose parent has no frame unit → `## <id>` + ```java block with body (and doc).
5. `fallbackWhenNoFrame` — only method units → concatenated contents, no `## ` header.
6. `spliceFallsBackToBeforeClosingBrace` — frame skeleton that does NOT contain the sig line (different text) → body inserted before the final `}` line, sig line untouched.
7. `skipsNothingForImportsUnits` — an imports unit is present → its content must NOT appear in the output (it is inside the frame already).
8. `mimeAndLanguage` — assert `"text/x-java-code"`.

## 5. Definition of Done

- Both files created; no other file touched; class is a Spring `@Component` (auto-discovered by `@ComponentScan("de.spraener.prjxp")` in the MCP server).
- `./gradlew :golden-retriever:test` green with all 8 tests; ≥ 80% LOC coverage of `JavaFileViewProvider` (all branches of `splice` exercised: match / annotation-strip / no-match / no-doc / no-closing-brace).
- `./gradlew build` green.
- Execute `/compact`, then return.
