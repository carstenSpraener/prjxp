# lp-csharp

C# Language Pack plugin for [prjxp](..). This is a **standalone Gradle project**
— it is *not* a subproject of the prjxp root build. It consumes `prjxp-common`
from the local Maven repository and bundles a C# parser generated from an
ANTLR grammar.

> **Status (WP-2 Phase 04, complete):** the full C# Language Pack — ANTLR parser,
> structural chunker (type frames, methods, constructors, fields, properties,
> events, indexers, XML docs, symbol metadata), `CSharpRetriever`,
> `CSharpLanguagePack` + `LanguagePackProvider` SPI, and the shadow fat-JAR
> packaging. The host (mcp-server hub + chunk-norris batch) loads it from
> `./language-packs` into its own class loader.
>
> **In progress (C# grammar upgrade):** the parser is being swapped from the C# v7
> grammar to the Roslyn grammar (C# 14). Phase 01 (grammar swap) is done; the
> chunker is a temporary stub until Phase 02 rewrites it for the Roslyn tree.

## Layout

```
lp-csharp/
├── build.gradle              # standalone build (java + ANTLR + shadow fat-JAR)
├── settings.gradle           # its own build root (not in the prjxp root)
├── src/
│   ├── main/
│   │   ├── antlr/.../grammar/
│   │   │   └── CSharp.g4           # MIT-licensed Roslyn grammar (see below)
│   │   ├── java/.../
│   │   │   ├── CSharpCodeChunker.java   # structural chunker (AST → PxChunks)
│   │   │   ├── CSharpRetriever.java     # retrieval side (part reassembly, enrichment)
│   │   │   ├── CSharpLanguagePack.java  # the pack (language, MIME, chunker, retriever, bind)
│   │   │   └── CSharpLanguagePackProvider.java  # SPI factory (no-arg create())
│   │   └── resources/META-INF/services/
│   │       └── de.spraener.prjxp.common.language.LanguagePackProvider
│   └── test/
│       ├── java/.../   # parser, chunker, retriever, pack/provider, plugin-JAR tests
│       └── resources/fixtures/*.cs
```

## The C# grammar — source and license

The parser is generated from the **Roslyn C# compiler grammar**
(`CSharp.Generated.g4`) in the [`dotnet/roslyn`](https://github.com/dotnet/roslyn)
repository (see `.grammar-source.txt` for the pinned upstream path). The
checked-in `CSharp.g4` is a hand-adapted copy: a single **combined** grammar
(lexer + parser in one file) with pragmatic whole-literal token rules, a
`DIRECTIVE` skip rule for `#` preprocessor lines, and no semantic predicates.

- **License:** **MIT** (.NET Foundation). The MIT copyright notice is retained
  in the grammar file header.
- **Compatibility:** MIT is a permissive license and is fully compatible with
  prjxp's **Apache License 2.0**.
- The grammar targets **C# 14** (the current standard): records, raw strings,
  file-scoped namespaces, union types, extension blocks, and everything below.
  C# 7 and earlier remain parseable as a subset.
- **`#` directives:** the Roslyn grammar's directive rules are dead code (never
  referenced) and `#` would otherwise be a real punctuation token, so the
  adapted grammar adds one lexer rule — `DIRECTIVE : '#' ~[\r\n]* -> skip;` —
  that skips directive lines. Code inside `#if`/`#endif` parses normally (no
  recall loss for a RAG index), and the directive text stays in the source so it
  flows 1:1 into chunk text via the chunker's line-range extraction.

## How the parser is generated

The C# grammar is a single **combined** file (`CSharp.g4`): one ANTLR
invocation generates both the lexer and the parser. String literals in parser
rules resolve against the grammar's own lexer rules (contextual keywords such
as `record` or `union` resolve to `identifier_token`). The build runs the
ANTLR tool on the combined grammar:

```
org.antlr.v4.Tool -o <out> -package de.spraener.prjxp.lpcsharp.grammar \
    -visitor CSharp.g4
```

This is wired up as the `generateCSharpGrammar` Gradle task (see
`build.gradle`), which runs before `compileJava`. The generated sources land in
`build/generated-src/antlr/main` and are added to the main source set — they are
**not** checked in and need no manual maintenance.

- ANTLR version: **4.13.1** (pinned in `build.gradle`).
- The ANTLR *tool* is isolated in the `antlrTool` configuration (build-time
  only); the ANTLR *runtime* is a regular `implementation` dependency.

## Dependencies

- **`de.spraener.prjxp:prjxp-common:0.0.1-SNAPSHOT`** — the shared host API
  (`LanguagePack`, `LanguagePackProvider`, `PxChunker`, `PxChunk`,
  `GoldenRetriever`, …). Resolved from the **local Maven repository**
  (`mavenLocal()`), *not* via a composite build. Publish it first:

  ```bash
  # from the prjxp root
  ./gradlew :prjxp-common:publishToMavenLocal
  ```

  If you republish `prjxp-common` (it is a `-SNAPSHOT`), rebuild with
  `--refresh-dependencies` to bypass Gradle's changing-module cache.

- **`org.antlr:antlr4-runtime:4.13.1`** — the ANTLR runtime (private
  dependency; loaded child-first when the plugin runs in the host).

## Build and test

```bash
cd lp-csharp
./gradlew build     # generates the parser, compiles, runs tests, builds the plugin JAR
./gradlew test      # just the tests
```

Tests: `CSharpParserSmokeTest` (parser smoke tests for the Roslyn grammar),
`CSharpCodeChunkerTest` (units, metadata, splitting — temporarily disabled until
the Phase 02 chunker rewrite), `CSharpRetrieverTest` (filtering, part reassembly,
parent enrichment, budgets), `CSharpLanguagePackProviderTest` (pack contract +
`bind()`), `PluginJarTest` (fat-JAR contents: SPI + ANTLR in, prjxp-common/slf4j
out), `PrjxpCommonContractTest` (host API contract present).

## Packaging

The plugin is a **shadow fat-JAR** (`build/libs/lp-csharp-plugin.jar`) that bundles
the ANTLR runtime (a private dependency, loaded child-first) but **never
`prjxp-common`** or `org.slf4j` classes (the shared host API, loaded parent-first —
a bundled copy would break class identity and the plugin is rejected at load time).
`PluginJarTest` verifies this on every build. See
[`doc/LanguagePack_Plugin_Guide.md`](../doc/LanguagePack_Plugin_Guide.md) in the
prjxp root for the full plugin contract.

The prjxp root build wires the plugin into the host tests: `:buildLpCsharpPlugin`
builds the JAR (after publishing `prjxp-common` to mavenLocal) and the
`stageLpCsharpPlugin` tasks of mcp-server/chunk-norris stage it for the
end-to-end tests.
