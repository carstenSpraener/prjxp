# 9. Design Decisions

ADR-style summary of the decisions that shape prjxp (status: all **Accepted**; most are
reflected in code and the phase documents under `doc/Tasks/` and `docs/`).

## D1 — Lucene as the only storage backend

- **Context:** earlier versions supported ChromaDB and MySQL alongside Lucene. Operating
  external DB services contradicted the "self-contained" goal; multi-backend code paths
  complicated every retrieval feature.
- **Decision:** remove all non-Lucene stores (commit "Lucene as the only storage").
  Lucene provides KNN vector search **and** full-text/index search in one file-based index.
- **Consequences:** (+) no DB service, trivial backup (copy a directory), one code path;
  (−) single-writer constraint → serialized hub pipeline; (−) no distributed scale-out
  (accepted: project indexes are small).

## D2 — JSONL as the intermediate pipeline format

- **Context:** chunking and embedding must be decoupled (different machines, different
  hardware profiles) yet stay simple to operate.
- **Decision:** one `PxChunk` per JSON line; streamable, pipe-able, diff-able; optional
  AES-256-GCM encryption for transfer.
- **Consequences:** (+) stages independently re-runnable; offline embedding workflow;
  (−) no schema evolution guarantees — the `PxChunk` contract is versioned with the app.

## D3 — Structural (AST-based) chunking with logical identity

- **Context:** sliding-window chunks destroy code structure; LLM answers about "method X"
  need the *whole* method even if it was split for embedding.
- **Decision:** chunk by language structure (imports, methods+javadoc, class frames);
  every unit gets a stable `id`, split parts share it (`part`/`total`), units link to
  their container via `parent`; `PxChunk.combine()` reassembles exactly.
- **Consequences:** (+) exact reconstruction of methods/classes/files; (−) one chunker per
  language (mitigated by the SPI, D5).

## D4 — Three-level search ladder (DynamicFocusedSearch)

- **Context:** LLM context must stay minimal; a single "search" tool either over- or
  under-delivers.
- **Decision:** stage retrieval — `vectorSearch` (semantic, skeletons by design) →
  `grep` (exact full-text, complete bodies on hit) → `readFile`/`readBySignature`
  (complete source views); unified `SearchHit` response format; tool descriptions steer
  the LLM through the ladder.
- **Consequences:** (+) token economy + precision; (−) more round-trips for deep questions
  (accepted: each level is cheap).

## D5 — SPI + annotation discovery for chunkers & vetoes

- **Context:** the project must support Java, TS, VB, Markdown, PDF, … and third parties
  must be able to add more without forking the core.
- **Decision:** `ServiceLoader` for brokers + classpath scan for `@ChunkNorrisComponent`
  classes with `@Chunker`/`@PostWalkChunker` methods; `@ChunkVeto` collected via
  `BeanPostProcessor`.
- **Consequences:** (+) drop-in JAR extensibility; (−) reflection-based wiring needs
  careful shadow-JAR `mergeServiceFiles()` packaging.

## D6 — Forest of Trees + veto system for context quality

- **Context:** multiple vector hits often refer to *different* classes; naive
  concatenation yields incoherent mega-contexts.
- **Decision:** group hits into trees via `parent` links (a forest), rank each tree,
  enrich per tree (frame + methods + javadoc with dedup), then apply veto/validator
  predicates and content budgets before concatenation.
- **Consequences:** (+) coherent, bounded context; (−) deterministic heuristics can still
  miss intent — mitigated by the fallback iteration and transparency reporting.

## D7 — One Docker image, four modes; hub pipeline in-process

- **Context:** per-project containers are operationally heavy for many projects; separate
  pipeline processes would need IPC and multi-writer index access (impossible in Lucene).
- **Decision:** one image with `entry.sh` modes `chunk|embed|serve|hub`; in hub mode the
  mcp-server process hosts chunk-norris/tibed as beans (CLI runners disabled via
  `prjxp.cli.enabled=false`); a single FIFO worker serializes all pipeline runs.
- **Consequences:** (+) one deployment unit, no IPC; (−) pipeline runs are serialized
  (accepted: embedding is I/O-bound on the external model server anyway).

## D8 — Shared index with project stamping (multi-project)

- **Context:** the hub must serve N projects from one index; per-project indexes would
  multiply memory and complicate addressing.
- **Decision:** all projects share one Lucene index; every chunk carries `pxchunk_project`;
  every DAO query is project-scoped; resets are scoped.
- **Consequences:** (+) one index to manage, cheap project add/remove; (−) a bad
  re-embed must never wipe other projects (hence scoped `removeAll`).

## D9 — Embedding prefix for method disambiguation

- **Context:** methods with identical bodies/names in different classes embed to nearly
  identical vectors → wrong-class hits.
- **Decision:** prepend `package ClassName` (the `embeddingPrefix`) to the embedded text
  of method chunks.
- **Consequences:** (+) measurably better class-level separation; (−) slightly longer
  embedded text.

## D10 — External embedding with encrypted transfer (offline workflow)

- **Context:** code may be sensitive; embedding hardware (GPU/TEI) often lives on a
  different machine.
- **Decision:** tibed `EXPORT`/`IMPORT` modes move records (with or without vectors) as
  AES-256-GCM-encrypted JSONL; password via `PRJXP_TRANSFER_PASSWORD`; AUTO mode encrypts
  iff a password is resolvable.
- **Consequences:** (+) code never leaves unencrypted; (−) manual transfer step.

## D11 — Zero manual Javadoc: self-documentation via docpipe (Documentation Paradox)

- **Context:** hand-written docs drift; maintaining them for a fast-moving code base is
  costly.
- **Decision:** no manual Javadoc by design; docpipe generates documentation from the
  project's own index (retrieval + LLM, Handlebars templates in `.dp/`); prjxp dogfoods
  this on itself.
- **Consequences:** (+) docs always reflect current code; (−) generated quality depends on
  retrieval quality and the chosen LLM.

## D12 — Shadow JAR instead of Spring Boot repackage

- **Context:** modules need fat JARs for Docker, but the Boot plugin's repackaging
  conflicted with multi-module shadow builds and metadata merging.
- **Decision:** `application` + shadow plugins; explicit merge/append of Spring metadata
  files (`spring.handlers`, `AutoConfiguration.imports`, …); DevTools excluded.
- **Consequences:** (+) uniform packaging, honest exit codes; (−) every new auto-config
  requires verifying the transform blocks.

## D13 — JaCoCo coverage ratchets (never lowered)

- **Context:** legacy modules had uneven test coverage; regressions were silent.
- **Decision:** per-module line-coverage minimums enforced on `check` (0.30–0.80);
  ratchets may only move up, after legacy code gets tested.
- **Consequences:** (+) coverage can only improve; (−) initial ratchets sit below the
  measured state to avoid blocking work.

## D14 — MCP HTTP Transport for docpipe's LLM (LangChain4j 1.21)

- **Context:** docpipe's LLM needed access to the prjxp MCP server's search tools
  (`vectorSearch`, `grep`, `readFile`, `readBySignature`) to query embedded project
  information during documentation generation. The existing LangChain4j MCP version
  (`1.13.0-beta23`) only supported `StdioMcpTransport` (subprocess), but the mcp-server
  runs as a Spring Boot web app on HTTP (`:7007/mcp`, streamable protocol).
- **Decision:** upgrade LangChain4j MCP to `1.21.0-beta31` (Oct 2026) which includes
  `StreamableHttpMcpTransport`; add HTTP transport branch to `McpClientManager.init()`;
  inject system prompt with `defaultProject` into `McPEnablingKIChatDecorator`; add
  `mcp-project` Handlebars resolver for explicit project context in prompt templates.
- **Consequences:** (+) docpipe's LLM can autonomously query the MCP server for project
  information; (−) requires mcp-server to be running and reachable at the configured URL;
  (−) LangChain4j beta version carries inherent instability risk (mitigated by comprehensive
  test coverage and the fact that MCP API is stable since `1.19.x`).
