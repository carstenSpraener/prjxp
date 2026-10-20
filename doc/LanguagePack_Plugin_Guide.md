# Language Pack Plugin Guide

How to build, deploy and operate external Language Packs for prjxp.

A **Language Pack** bundles everything prjxp needs to support one programming
language: a chunking side (files → `PxChunk`s) and a retrieval side (a
`GoldenRetriever`). Built-in packs (Java, TypeScript, Visual Basic) are Spring
beans inside the engine modules; **external packs are plugin JARs** that are
loaded at startup into the shared `LanguagePackRegistry` and become visible to
chunking, retrieval and the grep language filter alike.

## Building a plugin

A plugin is a plain JAR with three ingredients:

1. **A dependency on `prjxp-common`** (from the local Maven repository) — and
   *only* on `prjxp-common`. The plugin must not depend on chunk-norris,
   golden-retriever or any other prjxp module.
2. **A `LanguagePack` implementation** with your language's chunker(s) and
   retriever.
3. **An SPI service file** that registers a `LanguagePackProvider` factory:

   `META-INF/services/de.spraener.prjxp.common.language.LanguagePackProvider`
   containing the fully qualified provider class name, e.g.:

   ```
   com.example.csharp.CSharpPackProvider
   ```

   The provider is a no-arg factory:

   ```java
   public class CSharpPackProvider implements LanguagePackProvider {
       @Override
       public LanguagePack create() {
           return new CSharpPack();
       }
   }
   ```

   The provider pattern (instead of registering the pack itself) keeps the
   pack's construction inside the plugin class loader.

### Minimal example

```java
public class CSharpPack implements LanguagePack {
    @Override public String language() { return "csharp"; }
    @Override public String mimeType() { return "text/x-csharp-code"; }

    @Override
    public Stream<PxChunker> findPxChunkers(File f) {
        return Stream.of(new CSharpCodeChunker()).filter(c -> c.matches(f));
    }

    @Override
    public Optional<GoldenRetriever> retriever() {
        return Optional.of(new CSharpRetriever());
    }
}
```

## The shared host API

Everything a plugin may use comes from **`prjxp-common`** (plus the JDK):
`LanguagePack`, `LanguagePackProvider`, `PxChunker`, `PxChunk`,
`GoldenRetriever`, `ScoredChunk`, `SearchHit`, `SearchParams`, `PxChunkDao`,
… These types are loaded from the **host** class loader (see delegation rule),
so `instanceof` checks, casts and method dispatch across the class loader
boundary are safe.

## Delegation rule

Each plugin JAR gets its own isolating class loader with this delegation:

| Package | Delegation | Why |
|---|---|---|
| `de.spraener.prjxp.**` | **parent-first** (host) | the whole host API must keep one class identity |
| `org.slf4j.**` | **parent-first** (host) | plugin log output goes through the host logging |
| everything else | **child-first** (plugin) | private plugin libraries stay isolated |

## Golden rules

1. **Never bundle `de.spraener.prjxp.**` classes** into the plugin JAR. A
   bundled copy breaks class identity and the plugin is rejected at load time
   (the host logs an error naming the JAR).
2. **Never bundle `org.slf4j.**` classes** — same reason.
3. **Only `prjxp-common` types and JDK types may cross the boundary** (i.e.
   appear in your `LanguagePack`/`PxChunker`/`GoldenRetriever` signatures).
4. **Private libraries may be bundled** (e.g. your own ANTLR version). They
   are loaded child-first, so two plugins with *incompatible versions of the
   same private library* can coexist — each sees its own copy.
5. **One JAR per plugin.** Each JAR in the plugin directory is one plugin with
   its own class loader.

## Conflict rule

If a plugin provides a pack with the same `language()` value as a built-in
pack (e.g. `java`), **the plugin wins**: the built-in pack is replaced and the
host logs a clear warning naming both packs. If two plugins provide the same
language, the first one (deterministic JAR order) wins.

## Deployment

### Hub (mcp-server)

Place the plugin JARs in the plugin directory — default `./language-packs`
relative to the working directory, configurable via:

- property `prjxp.language-packs.dir`, or
- environment variable `PRJXP_LANGUAGE_PACKS_DIR` (relaxed binding).

### Batch (chunk-norris)

The same mechanism applies to the chunk-norris batch run: point
`prjxp.language-packs.dir` (or `PRJXP_LANGUAGE_PACKS_DIR`) at the plugin
directory, e.g.

```bash
PRJXP_LANGUAGE_PACKS_DIR=./language-packs java -jar chunk-norris-all.jar ...
```

so that the static Lucene index contains the plugin's chunks (e.g. C#) for
later retrieval.

### Docker

The runtime image declares `/app-source/language-packs` as a volume (the apps
run with `WORKDIR /app-source`, so the default `./language-packs` resolves
there). Mount your plugins at runtime — they are never baked into the image:

```bash
docker run -v ./language-packs:/app-source/language-packs ...
```

or override the location entirely:

```bash
docker run -e PRJXP_LANGUAGE_PACKS_DIR=/plugins -v ./language-packs:/plugins ...
```

## Lifecycle

- Plugins are loaded **once at startup** (before any Bean is created) and
  cached for the lifetime of the process. **There is no hot reload** — add,
  remove or change a plugin JAR and restart the application.
- On an orderly shutdown the host closes the plugin class loaders (releasing
  the JAR file handles).
- A broken plugin (unreadable JAR, missing SPI entry, contract violation,
  class identity break) is **rejected with an error log that names the JAR**
  and does not block the other plugins.
