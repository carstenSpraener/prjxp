# 10. Quality Requirements and Scenarios

## 10.1 Overview of Quality Goals (from [01](01_introduction_and_goals.md))

| ID | Quality Goal | Driver |
|---|---|---|
| QG-1 | Retrieval precision (relevant, coherent, bounded context) | LLM token economy & answer quality |
| QG-2 | Self-contained deployment (no external storage service) | ease of operation |
| QG-3 | Extensibility without core changes (new languages/formats) | product roadmap, third parties |
| QG-4 | Indexing performance (chunk + embed) at large-project scale | user patience, hub throughput |
| QG-5 | Security of project data (transfer, import) | sensitive code bases |
| QG-6 | Maintainability & testability (incl. for AI agents) | long-term evolution |

## 10.2 Quality Scenarios

Format: *Precondition → Stimulus → Response* (measurable where the code defines one).

### QG-1 — Retrieval precision

| # | Scenario |
|---|---|
| QS-1.1 | **Precondition:** index of a project with ≥ 50k chunks; the question refers to exactly one class. **Stimulus:** `vectorSearch` with default similarity 0.85. **Response:** the returned context contains that class's frame + hit methods with javadoc; unrelated classes appear only if their tree survives ranking/veto and the 60k-char global budget; skipped trees are reported. |
| QS-1.2 | **Precondition:** no chunk reaches the similarity threshold. **Stimulus:** `vectorSearch`. **Response:** fallback iteration widens results (+2 up to 16) then lowers the threshold in 0.05 grid steps; below 0.5 it aborts with an explicit "no valid context" message including the effective threshold and round count — never a silent low-quality dump. |
| QS-1.3 | **Precondition:** a method was split into N parts for embedding; only part k is hit. **Stimulus:** retrieval of that method. **Response:** all N parts are recombined (`PxChunk.combine`, overlap-aware) so the LLM sees the complete method. |
| QS-1.4 | **Precondition:** two classes contain methods with identical bodies. **Stimulus:** semantic query for that method. **Response:** the `embeddingPrefix` (package + class) keeps the vectors separable; hits resolve to the correct class. |

### QG-2 — Self-contained deployment

| # | Scenario |
|---|---|
| QS-2.1 | **Precondition:** a host with Docker only (no database, no GPU). **Stimulus:** `./prjxp <project> mcp`. **Response:** the full pipeline runs from one image; all state lives in volumes; the only external dependency is an embedding endpoint (CPU TEI or any OpenAI-compatible server). |
| QS-2.2 | **Precondition:** hub container restarted. **Stimulus:** startup. **Response:** projects already in the index come up `READY` without re-embedding; others re-run the pipeline — no data loss, idempotent. |

### QG-3 — Extensibility

| # | Scenario |
|---|---|
| QS-3.1 | **Precondition:** a third-party JAR on the classpath registers a `ChunkerBroker` via `META-INF/services`. **Stimulus:** chunk run over files of the new type. **Response:** the broker's `@Chunker` methods are discovered and used without any core modification. |
| QS-3.2 | **Precondition:** a new language needs file reading. **Stimulus:** add one `FileViewProvider` bean + mime registration. **Response:** `readFile` supports the language; unknown mimes fall back to `GenericFileViewProvider`. |

### QG-4 — Indexing performance

| # | Scenario |
|---|---|
| QS-4.1 | **Precondition:** project with 20k files, most of them irrelevant (build output, hidden, images). **Stimulus:** chunk run. **Response:** vetoes (build dirs outside `src`, >1 MB, hidden, images, whitelist) prune the walk before chunking; remaining files are processed in parallel streams. |
| QS-4.2 | **Precondition:** 10k chunks already embedded; 5% of files changed. **Stimulus:** re-embed run (`tibedResetStore=false`). **Response:** `StoreIdChecker` skips already-present chunk ids — only new/changed units hit the embedding API, in batches of `tibedBatchSize`. |
| QS-4.3 | **Precondition:** hub with a broad `/import` mount containing huge non-project trees. **Stimulus:** 5s poll cycle. **Response:** the marker scan prunes via `scan-exclude-dir-names`, `.prjxp-exclude` markers and the depth cap (8) — discovery stays bounded instead of walking every sibling repo. |
| QS-4.4 | **Precondition:** embedding provider rejects an oversized batch. **Stimulus:** embed run. **Response:** the failing batch is logged, the pipeline continues with remaining batches (no total failure). |

### QG-5 — Security

| # | Scenario |
|---|---|
| QS-5.1 | **Precondition:** `PRJXP_TRANSFER_PASSWORD` set; EXPORT mode. **Stimulus:** export run. **Response:** the transfer file is AES-256-GCM encrypted (key via PBKDF2WithHmacSHA256); without a password in explicit mode, a generated one is printed at start and end. |
| QS-5.2 | **Precondition:** hub import directory writable by an untrusted user. **Stimulus:** drop of a malicious tar (zip-slip paths, symlinks/hardlinks, >2 GB archive, >4 GB extracted, >100k entries). **Response:** extraction is rejected (`TarSecurityException`); the archive is renamed `*.tar.failed`; no files escape `/projects`. |
| QS-5.3 | **Precondition:** MCP endpoint reachable from the LAN. **Stimulus:** cross-origin browser request. **Response:** CORS is restricted to configured patterns (LAN/localhost); the compatibility filter only rewrites legacy protocol versions, never relaxes access. |

### QG-6 — Maintainability & testability

| # | Scenario |
|---|---|
| QS-6.1 | **Precondition:** any PR. **Stimulus:** `./gradlew build` in CI (JDK 21). **Response:** compilation + all tests pass and JaCoCo verification holds the per-module ratchets (0.30–0.80) — coverage can only grow. |
| QS-6.2 | **Precondition:** a contributor (human or AI agent) wants to understand a module. **Stimulus:** index the repo with prjxp itself and query via MCP. **Response:** structural chunks + generated docs answer "what does X do / where is Y" without manual Javadoc. |
