# 11. Risks and Technical Debt

## 11.1 Risks (current, with mitigations)

| # | Risk | Likelihood / Impact | Mitigation in place | Residual action |
|---|---|---|---|---|
| R1 | **Hub pipeline serialization:** one FIFO worker (Lucene single-writer) — many simultaneous imports queue up. | Med / Med | FIFO with idempotent enqueue; re-imports supersede in-flight runs; startup self-heal skips finished projects. | Consider per-project indexes or async embedding fan-out if hub scale demands it. |
| R2 | **Embedding model lock-in:** vector dimension is fixed per index (1024). Switching models invalidates all data. | Med / High | Docs state this explicitly; `LUCENE_VECTOR_DIMENSION` is a first-class env var. | Provide a re-embed helper / dual-index migration path. |
| R3 | **Broad `/import` mounts** make the 5s marker scan slow (huge sibling trees, slow bind mounts). | Med / Med | `scan-exclude-dir-names`, `.prjxp-exclude` markers, `scan-max-depth=8`; docs recommend narrow mounts. | None (documented); monitor poll duration in logs. |
| R4 | **Stale Lucene write lock** after a crash can block index open. | Low / High | Zero-byte `write.lock` is deleted and retried once; non-zero locks fail loudly. | None (heuristic accepted). |
| R5 | **MCP client fragmentation:** different clients speak different protocol versions. | Med / Low | `McpCompatibilityFilter` rewrites legacy `2025-06-18` requests. | Track Spring AI MCP protocol evolution; remove the filter once clients converge. |
| R6 | **amd64-only image** (TEI x86_64) → Rosetta overhead on Apple Silicon. | High / Low | Documented; `SKIP_EMBEDDING_SERVER` + external arm64-capable provider avoids it. | Provide an arm64 image variant without TEI. |
| R7 | **Generated documentation quality** (docpipe) depends on retrieval + LLM; wrong statements could be committed. | Med / Med | Output is clearly marked as generated (postscript); prompts instruct "say so if not in context". | Human review gate for committed docs. |

## 11.2 Technical Debt (known, tracked)

| # | Item | Evidence | Priority |
|---|---|---|---|
| TD-1 | **Low coverage ratchets** in `golden-retriever` (0.35) and `docpipe` (0.30) — the most heuristic-heavy modules are the least tested. | root `build.gradle` ratchets table | Medium — raise as tests land (ratchet only moves up). |
| TD-2 | **`readBySignature` is Java-only.** TS/VB chunkers do not write `symbol_*` metadata, so deterministic symbol lookup doesn't work for them (stated in the tool description). | `PrjxpMcpTool.readBySignature` docs; `SymbolMetadata` usage in Java chunker only | Medium. |
| TD-3 | **German strings in user-facing output** (e.g. `"[weitere %d Klassen wegen Groessenlimit nicht enthalten]"`, fallback messages, some prompts). | `JavaPromptSession.buildPrompt`, `GRPromptEnrichment` abort message, `GldRtrvrQuestioner` prompt | Low–Medium (i18n pass). |
| TD-4 | **`prjxp-launcher`** is included in `settings.gradle` but empty — a dangling placeholder. | `settings.gradle` line 20; empty directory | Low (fill or remove). |
| TD-5 | **oragel incubator** is not part of the default build — its drift from `gldrtrvr` APIs goes unnoticed. | `encubator/oragel`, absent from `settings.gradle` includes | Low (gate with a profile or CI job). |
| TD-6 | **Ad-hoc dependency versions** in a few module build files (`langchain4j-http-client-jdk:1.13.1`, Handlebars `4.5.1`) bypass the version catalog rule. | `prjxp-common/build.gradle`, `docpipe/build.gradle` | Low (move to catalog). |
| TD-7 | **Two MCP protocol configurations** coexist: `streamable` (mcp-server module yaml) vs `STATELESS/sse` (`application.yaml.docker` fallback baked into the image). | both files | Low–Medium (align before next release). |
| TD-8 | **`PxChunk.fromContentAndMap` NPE risk** on missing `pxchunk_id` (unconditional `.toString()`). | `PxChunk.java:92` | Low (guard like the other fields). |
| TD-9 | **`getModelName` in `PxChunkDaoProvider` returns the project name** (copy-paste smell; method name lies). | `PxChunkDaoProvider.java:47` | Low (rename/fix or delete if unused). |
| TD-10 | **`createMetaChunk` in `JavaCodeChunker` returns an empty list** — dead code path left from earlier design. | `JavaCodeChunker.java:237` | Low (remove or implement). |

## 11.3 Watch List (not debt, but to monitor)

- **Vector search score semantics:** Lucene KNN scores are not calibrated cosine
  similarities; the 0.85 default and the 0.05 fallback grid were tuned empirically per
  model — re-validate when the default embedding model changes.
- **`allow-bean-definition-overriding=true`** masks duplicate bean definitions across the
  wide `@ComponentScan("de.spraener.prjxp")` — keep an eye on startup logs for overrides.
- **JSONL contract evolution:** adding/renaming `PxChunk` fields affects old index data
  and transfer files; keep backward-compatible defaults.
