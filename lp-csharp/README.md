# lp-csharp

C# Language Pack plugin for [prjxp](..). This is a **standalone Gradle project**
— it is *not* a subproject of the prjxp root build. It consumes `prjxp-common`
from the local Maven repository and bundles a C# parser generated from an
ANTLR grammar.

> **Status (WP-2 Phase 01):** this project currently contains the C# **parser**
> only (grammar + generated parser + smoke tests). The chunker (Phase 02),
> member metadata (Phase 03) and the loadable plugin / retriever (Phase 04)
> are added in later phases.

## Layout

```
lp-csharp/
├── build.gradle              # standalone build (java + ANTLR generation)
├── settings.gradle           # its own build root (not in the prjxp root)
├── src/
│   ├── main/
│   │   ├── antlr/.../grammar/
│   │   │   ├── CSharpLexer.g4      # MIT-licensed grammar (see below)
│   │   │   └── CSharpParser.g4
│   │   └── java/.../grammar/
│   │       ├── CSharpLexerBase.java  # hand-written base class (preprocessor)
│   │       └── CSharpParserBase.java # hand-written base class (semantics)
│   └── test/
│       ├── java/.../CSharpParserTest.java   # parser smoke tests
│       └── resources/fixtures/*.cs          # test fixtures
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
./gradlew build     # generates the parser, compiles, runs tests, builds the jar
./gradlew test      # just the tests
```

The parser smoke tests (`CSharpParserTest`) cover a valid file, namespace,
class, method, and a syntax error. `PrjxpCommonContractTest` verifies the
`prjxp-common` contract is present.

## Packaging note (later phases)

The current `jar` contains only the plugin's own classes. When this becomes the
loadable plugin (Phase 04), it will be packaged as a **fat jar that bundles the
ANTLR runtime** (a private dependency, loaded child-first) but **never
`prjxp-common`** (the shared host API, loaded parent-first). See
[`doc/LanguagePack_Plugin_Guide.md`](../doc/LanguagePack_Plugin_Guide.md) in the
prjxp root for the full plugin contract.
