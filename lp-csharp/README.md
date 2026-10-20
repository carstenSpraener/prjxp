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

## Layout

```
lp-csharp/
├── build.gradle              # standalone build (java + ANTLR + shadow fat-JAR)
├── settings.gradle           # its own build root (not in the prjxp root)
├── src/
│   ├── main/
│   │   ├── antlr/.../grammar/
│   │   │   ├── CSharpLexer.g4      # MIT-licensed grammar (see below)
│   │   │   └── CSharpParser.g4
│   │   ├── java/.../
│   │   │   ├── grammar/            # hand-written base classes (preprocessor/semantics)
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

The parser is generated from the **C# v7 grammar** in the official
[`antlr/grammars-v4`](https://github.com/antlr/grammars-v4) repository
(`csharp/v7`), pinned to commit
[`3036ab7`](https://github.com/antlr/grammars-v4/commit/3036ab7a91d7b2ca2282ab6c3c9b945c0a92f8b0)
(see `.grammar-source.txt`).

- **License:** **MIT** (stated in the grammar header and the grammar's README).
  The header also carries a historical Eclipse Public License attribution to the
  original authors (Christian Wulf, Ivan Kochurkin / Positive Technologies);
  the grammar is distributed under MIT.
- **Compatibility:** MIT is a permissive license and is fully compatible with
  prjxp's **Apache License 2.0**. The MIT copyright notice is retained in the
  grammar file headers.
- The grammar targets **ECMA-334 7th edition** (C# 7.x and below).

The two hand-written base classes (`CSharpLexerBase`, `CSharpParserBase`) come
from the same grammar (its `Java/` directory) and are required by the grammar's
`superClass` options and semantic predicates. They were moved into the
`de.spraener.prjxp.lpcsharp.grammar` package (a `package` declaration was added).

## How the parser is generated

The C# grammar is a **separated lexer + parser** pair: `CSharpParser.g4`
references `CSharpLexer` via `tokenVocab` and uses string literals that must
resolve against the lexer's token vocabulary. The built-in Gradle `antlr`
plugin generates each `.g4` in an isolated ANTLR invocation, which breaks that
resolution. So the build runs the ANTLR tool **once on both files**, with
`-lib` pointing at the grammar directory:

```
org.antlr.v4.Tool -o <out> -package de.spraener.prjxp.lpcsharp.grammar \
    -lib <grammar-dir> CSharpLexer.g4 CSharpParser.g4
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

Tests: `CSharpParserTest` (parser smoke tests), `CSharpCodeChunkerTest` (units,
metadata, splitting), `CSharpRetrieverTest` (filtering, part reassembly, parent
enrichment, budgets), `CSharpLanguagePackProviderTest` (pack contract + `bind()`),
`PluginJarTest` (fat-JAR contents: SPI + ANTLR in, prjxp-common/slf4j out),
`PrjxpCommonContractTest` (host API contract present).

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
