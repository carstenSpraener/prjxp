# Changelog

All notable changes to prjxp are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/), and the project uses semantic
versioning.

## [0.9.1] — 2026-11-26

First release that ships prebuilt container images. The setup wizard now
survives container recreation and can be completed end to end, and
`package-info.java` files are indexed as package documentation.

### Added

- **Prebuilt multi-arch images:** `ghcr.io/carstenspraener/prjxp-hub` and
  `ghcr.io/carstenspraener/prjxp-frontend` are published on every `v*` tag for
  `linux/amd64` and `linux/arm64` — `:<tag>` always, `:latest` only for final
  tags. `docker compose` pulls them instead of building locally; the `build:`
  blocks remain as an offline fallback.
- **Package documentation as a chunk type:** `package-info.java` produces one
  whole-file `packageInfo` chunk (the package is parent and embedding prefix)
  instead of falling back to a raw file chunk with a `No value present` warning.
  golden-retriever injects it into the prompt and ranks it just below method
  documentation.
- **Warning for misplaced live projects:** an absolute `rootDir` that differs
  from the project's marker directory is logged at registration, because it
  silently sends chunking to a directory without sources.
- **Full-screen setup wizard:** the top bar, sidebar and heartbeat moved into a
  `ShellComponent`; `/setup` renders outside it and `setupIncompleteGuard`
  redirects any shell route back to the wizard until setup is completed
  (`completed == true` is enough, so "Später konfigurieren" still reaches the
  dashboard; the guard fails open if the status endpoint is unreachable).

### Changed

- **The setup configuration is persisted** as `application.yaml` on the
  `prjxp-hub-config` volume, seeded from the baked-in fallback on first start,
  so `prjxp.setup-completed` survives container recreation — the wizard no
  longer reappears after every `docker compose down`/`up`. The host `.env` is no
  longer bind-mounted read-only, and the wizard's secrets live on the writable
  `prjxp-hub-data` volume.
- **Docker builds run on the native builder architecture** (`$BUILDPLATFORM`):
  only the runtime stage is instantiated per platform, so the arm64 image no
  longer runs the Gradle and Angular builds under QEMU.
- The `./prjxp` control script mounts the project at `/app-source` in hub mode
  as well, so the wizard can persist its config there.
- `prjxp._yaml` drops the derived `rootDir`/`jsonlFile` keys; the marker
  directory is authoritative and the hub redirects live JSONL itself.

### Fixed

- **The embedding compatibility check ignored the embedding prefix.** tibed
  embeds `embeddingPrefix + "\n" + content`, the check embedded the bare
  content, so every index with prefixed chunks (Java class frames, methods,
  enum constants) looked incompatible at cosine ≈ 0.93 and "Test Embedding"
  answered `INDEX_REBUILD_REQUIRED` — even for an index the same model had just
  built.
- **Angular 22 froze the wizard on "Konfiguration wird geladen…"**: the default
  HTTP client uses the fetch backend, which emits outside the Angular zone, and
  components without an explicit strategy now default to `OnPush`. Both are
  pinned back to the zone-based behaviour the app is written for (`withXhr()`,
  `ChangeDetectionStrategy.Eager`).
- Prism `basic` is loaded before `vbnet` (which extends it), so the code viewer
  no longer throws `Cannot set properties of undefined` on page load.
- The Java symbol-type list includes `packageInfo`, so `symbolType` filtering
  and the exhaustive capability test agree again.

## [0.9.0] — 2026-11-25

First release since `v0.8.0-rc1`. The retrieval surface was reworked around fused
ranking, the graph layer gained a cluster view, and the build moved to Gradle 9.

### Added

- **Graphify V2:** `graphCluster` (cluster drill-down) and `graphClusterOverview`
  (architecture map) MCP tools, plus a `contentLevel` knob (`FULL` | `SKELETON` |
  `POINTERS`) that controls how much body a retrieval response carries.
- **Java/semantic knowledge graph:** chunk graph with Leiden clustering, rerank
  weights, and MCP views (`graphNode`, `graphFile`, …).
- **Docs browser:** in-app `/docs` page with tree + markdown rendering
  (`DocsService`/`DocsController`), nginx caching, baked into the hub image.
- **Fused ranking:** reciprocal-rank-fusion core shared by `grep` and
  `vectorSearch`; `vectorSearch` now fuses the semantic channel with an
  exact-token channel.
- **`grep` ranking signals:** match frequency, exact file-name matches, and a
  file-list default (`grep -l` semantics; lines only on request).
- **Live updates:** generatedAt watermark, `LiveUpdateScanner`, and a Web-UI
  update indicator with progress.
- **Model tooling:** `EmbeddingModelSupplier`/`ModelLister`, model test and list
  endpoints, and Test/Read buttons in the model dialogs.
- **AOAI embedding models** (Azure OpenAI) as a first-class provider.
- **GDScript language pack** with host wiring and E2E tests; lossless C#
  `readFile` via `FileViewProvider`.
- **Text fallback chunker:** `DefaultLineBreakTextChunker` plus a text retriever,
  so whitelisted files without a language chunker are indexed instead of being
  dropped.
- Benchmark suite for retrieval quality (grep-ability classes, A/B runs,
  guardrails).

### Changed

- **Gradle 9.9.2** and **Shadow 9.6.1** for all three builds; foojay resolver 1.0.0.
  CI actions refreshed (`checkout@v7`, `setup-java@v6`, `setup-gradle@v6`).
- **Lucene 10.6.0.**
- One tool surface with one short guide and cheap defaults instead of a profile
  switch and five tuning knobs; tool descriptions tightened.
- Pointer-based responses: dependency chunks and text/markdown retrievers emit
  hints instead of full content; `readBySignature` retries a missed symbol.
- The embedding request cap now applies on every model path; the log states what
  the pipeline actually does.
- `listFiles` lists directories like `ls` (trailing slash), sorts before
  limiting, and enumerates path terms instead of scanning documents.
- `prjxp`'s own data directory is excluded from the index.

### Fixed

- **`readBySignature` class-FQN lookup** (known bug in `v0.8.0-rc1`).
- **Empty `projectName` in hub mode** no longer errors with the `'cwd'` quirk.
- **Embedded chunks are immediately searchable** (blocking NRT refresh instead of
  an eventual async refresh).
- **Whitelisted files without a chunker** are no longer silently dropped.
- File-scoped reset for document re-embedding.
- 1-based chunk anchors in `grep` and `readBySignature` line numbers.
- `TermInSetQuery` for `IsIn`/`IsNotIn`, so live updates survive more than 1024
  files.
- `apiKey` is excluded from `toString()` in the configuration classes.

### Removed

- **In-container TEI embedding server.** The `text-embeddings-router` binary was
  already gone from the image; the `SKIP_EMBEDDING_SERVER` switch and the
  `entry.sh` process management around it were dead code. Embeddings are always
  an external OpenAI-compatible endpoint (LM Studio, OpenAI, AOAI, Ollama, TEI).
- `libgomp1` from the runtime image (OpenMP runtime of the removed inference
  engine).
- The amd64-only image constraint and the Apple Silicon Rosetta note — with no
  inference engine in the image, nothing pins it to a CPU architecture.
- The `MixedBredEmbedding` / `MixedBredEmebeddingClient` stubs; `embed()` returned
  `null` and the beans were never injected.

[0.9.1]: https://github.com/carstenSpraener/prjxp/compare/v0.9.0...v0.9.1
[0.9.0]: https://github.com/carstenSpraener/prjxp/compare/v0.8.0-rc1...v0.9.0
