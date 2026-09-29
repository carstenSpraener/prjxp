# 3rd Level File Reading — Overview

## What We Build

A **Reader** feature for the MCP server: the 3rd search level after `vectorSearch` (discovery) and `grep` (exact string).

```
vectorSearch  →  grep  →  readFile / readBySignature
 (semantic)    (exact)   (whole source view, reconstructed from the index)
```

Two new `@McpTool`s on `PrjxpMcpTool`:

| Tool | Input | Output |
|---|---|---|
| `readFile` | `file` (lenient path), `project?`, `offset?`, `limit?` | `FileView` JSON record |
| `readBySignature` | `method` (`FQN#name` or bare name), `container?`, `project?` | `SymbolReadResult` JSON record (max 10 full `MethodView`s + `remainingFqns`) |

## Core Decisions (settled in grilling, Q1–Q7)

1. **Index is ALWAYS the source of truth.** Never read the disk, no disk fallback. The index is built from a (legacy) project; the source may not exist anymore.
2. **Output is a "source view", not a byte-identical file.** Whitespace/comments *between* units are not stored in the index. The view splices stored units into a readable structure. `FileView.chunkCount` carries transparency about the reconstruction.
3. **Per-language readers + generic fallback.** Language is auto-detected from chunk `mimeType` (no `language` parameter). Unknown language → `GenericFileViewProvider` (fromLine-ordered, overlap-deduplicated concatenation).
4. **Layering:**
   - `prjxp-common` — model records, `FileViewProvider` SPI, `GenericFileViewProvider`, `ReaderSupport` util. (No Spring annotations.)
   - `golden-retriever` — per-language readers (`JavaFileViewProvider`, `TypeScriptFileViewProvider`, `VisualBasicFileViewProvider`, `MarkdownFileViewProvider`) as `@Component`s.
   - `mcp-server` — `FileViewRegistry`, `ReaderService`, `SymbolReaderService`, the two `@McpTool`s, `PrjXPConfig` cap. **No REST controller** (MCP-only; existing byIndex/grep REST untouched).
5. **Output cap:** 100k chars, configurable via `prjxp.reader-max-output-chars` (new field in `PrjXPConfig`).
6. **readBySignature is Java-only in Phase 1** (only the Java chunker writes `symbol_*` metadata). Stated explicitly in the tool description.
7. **No new dependencies.** Pure Java + existing modules. Nothing in the version catalog.

## Key Data Model Facts (verified)

`PxChunk` (prjxp-common, `de.spraener.prjxp.common.model`): `@Data` class with `id, mimeType, file, parent, part(int), total(int), fromLine(String), toLine(String), size(int), overlap(int), embeddingPrefix, Map<String,String> metadata, content`.
- `PxChunk.combine(List<PxChunk>)` reassembles all parts of one unit (sort by `part`, unsplit with overlap). **Parts of one unit share the same `id`.**
- File paths in the index are rootDir-relative with a **leading slash** (e.g. `/src/Foo.java`) — `ChunkProcess` does `file.replace(rootDirAbs, "")`.

### Unit structure per language

| Language | mime | section key | Units (id / parent / section) |
|---|---|---|---|
| Java | `text/x-java-code` | `java_code_section` | classFrame `FQN` (root, synthesized skeleton: package+imports+javadoc+header+fields+sig lines+`}`) · method `FQN.sig` / parent `FQN` (real source lines, annotations included) · methodDoc `FQN.sig.javadoc` / parent method id · imports `FQN.imports` (redundant — inside frame). Sig = `m.getDeclarationAsString(false,false,false)` (no params). |
| TypeScript | `text/x-typescript-code` | `typescript_code_section` | classFrame `module.Class` (synthesized: imports+class line+JSDoc+props+sig lines `{`→`;`+`}`) · method `module.Class.name` / parent `module.Class` (body prefixed with synthetic `\t// Method %s in class %s:` when no JSDoc) · methodDoc `...jsdoc` (also prefixed) · top-level function `module.fn` / parent `module` · imports. **No `symbol_*` metadata.** |
| Visual Basic | `text/x-visual-basic-code` | `visualbasic_code_section` | classFrame `module.Container` (root; **real file lines** with member bodies collapsed to their first line) · method `parentId.memberName` / parent `parentId` · methodDoc `...doc` (comment lines already remain in the frame) · header/imports. Overloads: `name.overloadN`. **No `symbol_*` metadata.** |
| Markdown | `text/markdown` | — (metadata `pxchunk_type`) | FILE (empty content, id=fileName) · SECTION (content = header text, id `file:1.02`) · paragraphs (real content, id `file:1.02:NN`, parent = section id, 500/100 split). |

### Store access

`PxChunkDao.searchByIndex(Map<String,String> filters, int limit)` — **uncapped**, exact `TermQuery` per filter key (keys go through `PxChunk.metadataFieldKey`), Lucene-only. `findByMetaData` is capped at 100 — **do not use it** for file lookup. Empty filter map → `MatchAllDocsQuery`.

`PxChunkDaoProvider.get(projectName)` → `Optional<PxChunkDao>` (`"default"` → the default store). Project resolution pattern (from `ByIndexSearchService`): blank/`"default"` → `cfg.getActiveProject().map(ProjectDefinition::getName).orElse("default")`.

Bean discovery: `McpServer` carries `@ComponentScan("de.spraener.prjxp")` — `@Component`s in any module under that root are visible to the MCP server.

## Phase Map (build order, each phase independently green)

| Phase | Module | Content |
|---|---|---|
| 01 | prjxp-common | `FileView`, `MethodView`, `SymbolReadResult` records · `FileViewProvider` SPI · `GenericFileViewProvider` · `ReaderSupport` (combineUnits / normalizePath / parseLine) + tests |
| 02 | golden-retriever | `JavaFileViewProvider` (frame skeleton + line-based method/doc splice) + tests |
| 03 | golden-retriever | `TypeScriptFileViewProvider` + `VisualBasicFileViewProvider` + tests |
| 04 | golden-retriever | `MarkdownFileViewProvider` + tests |
| 05 | mcp-server | `FileViewRegistry` · `ReaderService` (path leniency, dispatch, offset/limit, cap) · `readerMaxOutputChars` in `PrjXPConfig` · `readFile` tool · vectorSearch/grep description updates + tests |
| 06 | mcp-server | `SymbolReaderService` · `readBySignature` tool · description finalization + tests |

**Build & test:** `./gradlew build` (full) · `./gradlew :prjxp-common:test :golden-retriever:test :mcp-server:test` (incremental). Tests: JUnit 5 + AssertJ + Mockito, plain unit tests (no Spring context), ≥ 80% LOC coverage of new classes.
