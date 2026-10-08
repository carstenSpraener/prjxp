# 5. Building Block View (Static)

## 5.1 Whitebox: Whole System (Level 0)

```mermaid
flowchart TB
    subgraph apps ["Executable applications (shadow fat JARs)"]
        CN["chunk-norris<br/>CLI: chunk → JSONL"]
        TIB["tibed<br/>CLI: embed / export / import"]
        MS["mcp-server<br/>MCP + REST + Hub runtime"]
        DP["docpipe<br/>CLI: doc generation"]
    end

    subgraph libs ["Library modules"]
        GR["golden-retriever<br/>RAG engine"]
        LS["lucene-store<br/>Lucene index + DAO"]
        PC["prjxp-common<br/>shared kernel"]
    end

    ORA[("oragel — incubator,<br/>not in default build")]

    MS --> GR
    MS --> LS
    MS -.->|"hub: in-process pipeline"| CN
    MS -.->|"hub: in-process pipeline"| TIB
    DP --> GR
    CN --> PC
    TIB --> LS
    TIB --> PC
    GR --> LS
    GR --> PC
    LS ==>|"api"| PC
    ORA -.-> GR

    style PC fill:#e8f0fe,stroke:#4285f4
    style LS fill:#fef7e0,stroke:#f9ab00
```

**Rationale:** strict layering — every module depends only on `prjxp-common` (the shared
kernel) and, where retrieval/storage is needed, on `lucene-store`. Only the *applications*
compose libraries; `mcp-server` is the only module that sees all of them (it must, because
hub mode runs the whole pipeline in one process). `lucene-store` exposes `prjxp-common`
as **api** (its DAO types appear in its public signatures).

### Contained building blocks (Level 1 black boxes)

| Black box | Purpose / Responsibility | Interface(s) | Quality characteristics |
|---|---|---|---|
| **prjxp-common** (`de.spraener.prjxp.common`) | Shared kernel: `PxChunk` model + metadata keys, config binding (`PrjXPConfig`, `ProjectDefinition`, store/chat/MCP references), chat abstraction (`KIChat`, `KIChatProvider` + per-server suppliers: Ollama, OpenAI-compatible, Gemini, LM Studio, Azure), **MCP client infrastructure** (`McpClientManager` with stdio + **Streamable HTTP transport**, `McPEnablingKIChatDecorator` for tool-enabled LLMs with system prompts), store abstractions (`PxChunkDao`, `PxChunkDaoProvider`), transfer crypto (AES-256-GCM/PBKDF2), annotations (`@Chunker`, `@ChunkVeto`, `@Retriever`, `@PreWalk`, `@PostWalkChunker`, `@ChunkNorrisComponent`), capability registry types, reader SPI (`FileViewProvider`), error log service. | Java API (no own process) | Dependency of all modules; keeps the `PxChunk` contract in exactly one place. LangChain4j MCP **1.21.0-beta31** for `StreamableHttpMcpTransport`. |
| **chunk-norris** (`de.spraener.prjxp.chuno`) | CLI chunking framework: walks a project tree, applies vetoes, selects & runs chunkers per file (SPI), writes JSONL (optionally encrypted). | `ChunkNorris` main; `ChunkerBroker` SPI (`META-INF/services/de.spraener.chuno.ChunkerBroker`); `PxChunker`; `@Chunker`/`@PostWalkChunker` discovery; `VetoRegistry`. | Parallel file processing; per-file fault isolation (parse errors → empty stream, run continues). |
| **lucene-store** (`de.spraener.prjxp.lucene`) | File-based vector + full-text index: `LuceneEmbeddingStore` (LangChain4j `EmbeddingStore`, KNN search, filter-based delete), `LucenePxChunkDao` (project-scoped DAO: vector/full-text/index search, find-by-id/metadata), Spring auto-configuration. | `EmbeddingStore<TextSegment>`, `PxChunkDao`; `LuceneStoreAutoConfiguration`. | Single-writer index; stale-lock recovery; read/write locking. |
| **tibed** (`de.spraener.prjxp.tibed`) | Batch embedding engine: reads JSONL in batches, embeds via LangChain4j `EmbeddingModel`, writes to the store. Modes: **STORE** (default), **EXPORT** (chunks → `embedding.jsonl` with vectors, encrypted transfer), **IMPORT** (`embedding.jsonl` → store). | `TiBedCliApp` main; `EmbeddingService.executeForProject()` (in-process use by hub). | Incremental embed (`StoreIdChecker` skips existing ids); project-scoped reset; batch size per project. |
| **golden-retriever** (`de.spraener.prjxp.gldrtrvr`) | RAG engine: `GoldenRetriever` implementations per language (Java, TypeScript, Visual Basic, Markdown), chunk ranking services/rankers, prompt sessions with **Forest of Trees** reconstruction, `GRPromptEnrichment` (search + fallback iteration + budgets), per-language `FileViewProvider`s, Javadoc enricher. | `GoldenRetriever` (buildPromptForFindings / retrieveSearchHits), `GRPromptEnrichment.enrich(...)`, `FileViewProvider` beans. | Deterministic context construction; global content budget across retrievers. |
| **mcp-server** (`de.spraener.prjxp.mcp`) | Runtime: Spring AI MCP server (streamable) with tools `vectorSearch`, `grep`, `readFile`, `readBySignature`, `listProjects`; REST API + OpenAPI; project registries (static / hub); search capability registry; reader services; **hub package** (import poller, tar extractor with security limits, pipeline orchestrator, lifecycle service, projects REST + web UI). | `McpServer` main; MCP endpoint `/mcp`; REST `/prjxp/*`; web UI `/`. | Strictest coverage ratchet (0.80); compatibility filter for legacy MCP protocol versions. |
| **docpipe** (`de.spraener.prjxp.docpipe`) | Documentation pipeline: reads jobs from `.dp/documents.json`, resolves Handlebars prompt templates via pluggable resolvers (`gr` → golden-retriever enrichment, `source-dump`, `source-skeleton`, `groovy` scripts, `url`, `current-file`, **`mcp-project`** → project context for MCP tool usage), calls LLMs by stereotype, writes output files. The LLM can use **MCP tools over HTTP** (via `McPEnablingKIChatDecorator`) to query the prjxp MCP server (`vectorSearch`, `grep`, `readFile`, `readBySignature`) for embedded project information during content generation. | `DocPipeCliApp` main; `.dp/` job configuration directory. | Parallel task execution (fixed pool, `prjxp.docpipe.maxthreads`); misconfigured jobs degrade to empty job instead of aborting; MCP tool loop runs transparently inside `String chat(String)`. |
| **oragel** (`de.spraener.prjxp.oragel`, *incubator*) | Experimental interactive CLI: prompt sources + enrichment events, console log listener. Scans `gldrtrvr` beans. | `OragelCliApp` main. | **Not included** in the default `settings.gradle`; experimental, no stability promise. |

> Placeholder: `prjxp-launcher` is listed in `settings.gradle` but contains no sources yet
> (reserved for a future unified launcher).

## 5.2 Level 2 — Whitebox per Module

### 5.2.1 prjxp-common

```mermaid
flowchart LR
    subgraph model ["model"]
        PX["PxChunk<br/>id mime file parent part/total<br/>fromLine/toLine size/overlap<br/>embeddingPrefix project metadata"]
        SH["SearchHit"]
        FV["FileView / MethodView<br/>SymbolReadResult"]
        SM["SymbolMetadata<br/>symbol_* keys + SHA-256 sig hash"]
    end
    subgraph config ["config"]
        CFG["PrjXPConfig<br/>prjxp.* binding:<br/>activeProject, projects[],<br/>embeddingStores[], chatModels[],<br/>mcpServers[], transfer"]
        PD["ProjectDefinition<br/>name rootDir jsonlFile<br/>chunoWhiteList tibedBatchSize<br/>tibedResetStore"]
    end
    subgraph chat ["chat"]
        KIP["KIChatProvider<br/>byName / byStereotype"]
        KIM["KIChatModelProvider<br/>supplier loop + cache"]
        SUP["ChatModelSuppliers:<br/>Ollama   OpenAI-compatible<br/>Gemini   LMStudio   Azure"]
        KIW["KIChatModelWrapper / KIChat<br/>+ McPEnablingKIChatDecorator<br/>(system prompt + MCP tool loop)"]
    end
    subgraph store ["store"]
        DAO["PxChunkDao<br/>findById findByMetaData<br/>findRelevant searchFullText<br/>searchByIndex searchVector"]
        DAP["PxChunkDaoProvider<br/>register/unregister   get(name)"]
    end
    subgraph transfer ["transfer"]
        TC["TransferCrypto<br/>AES-256-GCM + PBKDF2"]
        TS["TransferSession /<br/>PasswordResolver / Mode"]
    end
    ANN["annotations:<br/>@Chunker @ChunkVeto<br/>@Retriever @PreWalk<br/>@PostWalkChunker<br/>@ChunkNorrisComponent"]
    CAP["capability:<br/>LanguageCapability  <br/>SearchParamDef   IdentifierRules"]
    RDR["reader SPI:<br/>FileViewProvider  <br/>GenericFileViewProvider   ReaderSupport"]
    ERR["PxLogService / PxLogMessage<br/>error log + exit-code semantics"]
    SCR["ScriptCompileService<br/>Groovy"]

    KIP --> KIM --> SUP
    KIM --> KIW
    DAP -.-> DAO
```

Key interfaces:

- `PxChunkDao` — the storage contract used by all retrieval code. Default methods
  (`searchFullText`, `searchByIndex`, `searchVector`) throw `UnsupportedOperationException`
  so non-Lucene stores degrade gracefully.
- `PxChunkDaoProvider.get(projectName)` — `"default"` resolves to the store reference with
  `isDefault=true`; supports runtime `register`/`unregisterByProject` (hub).
- `KIChatProvider.getByStereotype(...)` — docpipe selects LLMs by stereotype (e.g.
  `javadoc`); the MCP client manager decorates chats with external MCP tools via
  `McPEnablingKIChatDecorator` (supports **stdio** and **Streamable HTTP** transports,
  injects system prompt with `defaultProject` context).

### 5.2.2 chunk-norris

```mermaid
flowchart LR
    MAIN["ChunkNorris<br/>@SpringBootApplication<br/>bean 'chunoRun'<br/>prjxp.cli.enabled gate"]
    ARGS["CliArgsParser<br/>+ PrjXPConfig binding"]
    PROC["ChunkProcess<br/>walk   vetoes   dispatch<br/>JSONL writer (± encrypted)<br/>post-walk"]
    FACT["ChunkerFactory<br/>extends AnnotationBasedChunkerBrokerImpl<br/>+ ServiceLoader brokers"]
    VETO["VetoRegistry<br/>BeanPostProcessor over @ChunkVeto<br/>StandardVetos:<br/>build artifacts   size>1MB<br/>hidden   images   whitelist"]
    subgraph chunkers ["PxChunker implementations"]
        JC["JavaCodeChunker<br/>JavaParser: imports   methods+javadoc<br/>class frames   symbol_* metadata"]
        TSC["TypeScriptCodeChunker"]
        VBC["VisualBasicCodeChunker"]
        MC["MarkdownChunker<br/>file/section/paragraph units"]
        TC2["TextChunker   PdfChapterChunker<br/>+ doc conversion agents:<br/>PDF→MD(Tika)   HTML→MD   DOCX/DOC→HTML<br/>RTF→HTML   TXT→MD   Image→MD"]
        DEPS["JavaDependenciesChunker<br/>(post-walk, jgrapht project graph)"]
    end
    MAIN --> ARGS --> PROC
    PROC --> VETO
    PROC --> FACT --> chunkers

    style JC fill:#e8f0fe,stroke:#4285f4
```

- **Selection logic:** `ChunkerFactory` aggregates its own annotation-discovered chunkers
  plus all `ServiceLoader<ChunkerBroker>` registrations; for a file, every matching
  chunker (`PxFileType` suffix match via `@Chunker(fileTypes=…)`) runs.
- **Java unit structure** (mime `text/x-java-code`, section key `java_code_section`):

  | Unit | id | parent | content |
  |---|---|---|---|
  | imports | `FQN.imports` | `FQN` | import block (also inside frame) |
  | methodDoc | `FQN.sig.javadoc` | method id | javadoc text |
  | method | `FQN.sig` (sig = declaration w/o params) | `FQN` | annotations + real source lines |
  | classFrame | `FQN` (root) | — | synthesized skeleton: package + imports + javadoc + header + fields + signature lines + `}` |

  Long units are split by `ContentSplitter` (`prjxp.java.chunksize:1000`,
  `chunkoverlap:100`) into parts sharing the unit id. Method chunks carry an
  `embeddingPrefix` (`package ClassName`) for vector disambiguation and `symbol_*`
  metadata (FQN, name, type, container FQN, SHA-256 signature hash) for `readBySignature`.
- **Document pipeline:** `DocConversionRouter` + `ConversionRoutesConfig` route formats to
  conversion agents (accuracy/cost-estimated routes); results feed the Markdown/Text/PDF
  chunkers. `MetaInfReader` handles META-INF resources.

### 5.2.3 lucene-store

```mermaid
flowchart LR
    subgraph store ["LuceneEmbeddingStore"]
        W["IndexWriter<br/>NIOFSDirectory   stale-lock recovery"]
        S["SearcherManager + ReadWriteLock"]
        KNN["KnnFloatVectorQuery<br/>search(EmbeddingSearchRequest)"]
        FT["TextField 'content' +<br/>StringFields for metadata<br/>(pxchunk_* keys)"]
        RM["removeAll(Filter)   hasMatch   commit"]
    end
    DAO["LucenePxChunkDao<br/>project-scoped filters:<br/>findRelevant (KNN)   searchFullText (TermQuery MUST+FILTER)<br/>searchByIndex (exact, uncapped)   findById/findByMetaData"]
    AC["LuceneStoreAutoConfiguration<br/>@Bean store + one DAO per embeddingStores[] entry"]
    W --> S --> KNN & FT
    AC --> store
    AC --> DAO
```

- Documents store: `content` (analyzed), all `pxchunk_*` metadata as string fields,
  the KNN vector (`KnnFloatVectorField`, dimension validated on write).
- `LucenePxChunkDao` scopes **every** query with `pxchunk_project = <project>` — this is
  the multi-project separation mechanism.

### 5.2.4 tibed

```mermaid
flowchart LR
    MAIN["TiBedCliApp<br/>bean 'tibedRun'<br/>mode switch on transfer.mode"]
    subgraph modes ["execution modes"]
        ES["EmbeddingService<br/>STORE: JSONL → embed → store<br/>scoped reset   incremental"]
        EX["EmbeddingExportService<br/>EXPORT: JSONL → embedding.jsonl (± encrypted)"]
        IM["EmbeddingImportService<br/>IMPORT: embedding.jsonl → store"]
    end
    EXE["EmbeddingExecutor<br/>batched embed calls"]
    EM["LangChain4JEmbedderImpl /<br/>MixedBredEmbedding<br/>(embeddingPrefix-aware)"]
    SUP["EmbeddingStoreSupplier<br/>store per project"]
    SIC["StoreIdChecker<br/>needsImport(id, project) → skip if present"]
    MAIN --> modes --> EXE --> EM & SUP
    ES --> SIC

    style ES fill:#e8f0fe,stroke:#4285f4
```

- `EmbeddingService.executeForProject(pd, store)` is the in-process entry point used by
  the hub; it stamps `project` on every chunk **before** the incremental check.

### 5.2.5 golden-retriever

```mermaid
flowchart LR
    subgraph enrich ["enrichment"]
        GRE["GRPromptEnrichment<br/>vector search (window 50) →<br/>retriever loop   global budget<br/>fallback iteration (grid 0.05, abort below 0.5)"]
        SP["SearchParams<br/>maxResult   minScore   skeletonsOnly<br/>effectiveMinScore   fallbackRounds"]
    end
    subgraph retrievers ["GoldenRetriever implementations"]
        JR["JavaRetriever<br/>combine by id → JavaPromptSession"]
        TRS["TypeScriptRetriever"]
        VRB["VisualBasicRetriever"]
        MRD["MarkdownRetriever"]
    end
    subgraph session ["JavaPromptSession — Forest of Trees"]
        CN2["ChunkNode graph<br/>built from parent links"]
        RANK["ChunkRankingService +<br/>JavaChunkRanker / TS / VB / MD"]
        MOD["JavaPromptModifier<br/>frame + methods + javadoc splice,<br/>skeleton mode"]
    end
    subgraph readers ["FileViewProvider beans"]
        JFV["JavaFileViewProvider<br/>frame + method/doc splice by line coords"]
        TFV["TypeScriptFileViewProvider"]
        VBV["VisualBasicFileViewProvider"]
        MDFV["MarkdownFileViewProvider"]
    end
    JD["JavaDocEnricher<br/>(generated javadoc events)"]

    GRE --> SP
    GRE --> retrievers
    JR --> session
    readers -.->|"used by mcp-server ReaderService"| GRE
```

- `GRPromptEnrichment.enrich(project, prompt, …)` is the single entry point used by MCP
  `vectorSearch`, REST `/context` and docpipe's `gr` resolver.

### 5.2.6 mcp-server

```mermaid
flowchart LR
    MAIN["McpServer<br/>@ComponentScan de.spraener.prjxp"]
    subgraph mcp ["MCP layer (streamable HTTP /mcp)"]
        TOOL["PrjxpMcpTool<br/>vectorSearch   grep   readFile<br/>readBySignature   listProjects"]
        COMPAT["McpCompatibilityFilter<br/>legacy protocol 2025-06-18 rewrite"]
    end
    subgraph rest ["REST layer /prjxp/*"]
        RC["McpRestController<br/>ping   context   projects"]
        VSC["VectorSearchController"]
        GC["GrepSearchController"]
        BC["ByIndexController"]
        MSC["MetaSearchParamsController"]
    end
    subgraph core ["services"]
        VSS["VectorSearchService"]
        GSS["GrepSearchService"]
        RS["ReaderService<br/>lenient path   paging   100k cap"]
        SRS["SymbolReaderService<br/>readBySignature (Java)"]
        FVR["FileViewRegistry<br/>mime → provider"]
        REG["ProjectRegistry:<br/>StaticProjectRegistry (config)<br/>HubProjectRegistry (dirs, runtime)"]
        SCR2["SearchCapabilitiesRegistry<br/>+ JavaSearchCapabilitiesProvider"]
    end
    subgraph hub ["hub package — @ConditionalOnProperty prjxp.hub.enabled"]
        POLL["ImportPoller<br/>5s: tars + prjxp.yaml markers<br/>exclude names   max depth"]
        TAR["TarExtractor<br/>2GB/4GB/100k limits   zip-slip guard"]
        ORCH["PipelineOrchestrator<br/>single FIFO worker:<br/>chunk → scoped reset+embed"]
        LIFE["ProjectLifecycleService<br/>importing→chunking→embedding→ready/failed"]
        HPC["HubProjectsController<br/>GET/DELETE /prjxp/projects   POST reindex"]
        UI["static web UI<br/>index.html + component.js"]
    end

    MAIN --> mcp & rest & core
    TOOL --> VSS & GSS & RS & SRS & REG
    rest --> core
    hub --> ORCH
    POLL --> TAR --> ORCH
    ORCH --> LIFE
    HPC --> REG
```

- `McpServer` scans the **whole** `de.spraener.prjxp` root — this is how all module beans
  (chunkers, retrievers, readers, the Lucene store) become visible in one process.
- Bean-name discipline: CLI runners are named `chunoRun`/`tibedRun` and gated by
  `prjxp.cli.enabled` (hub sets it to `false`).

### 5.2.7 docpipe

```mermaid
flowchart LR
    MAIN["DocPipeCliApp<br/>dotenv → Spring"]
    ARGS["DocPipeArgsParser"]
    RUN["DocPipeRunner<br/>jobs → tasks   fixed pool (maxthreads 5)<br/>SEVERE errors → exit 1"]
    subgraph cfg ["config package"]
        JOBS["JobCreationService<br/>.dp/documents.json → DPJob"]
        MODELS["ModelConfigLoader<br/>chat-model stereotypes"]
        DOT["DotDPFilesService   EnvResolver"]
    end
    subgraph content ["content package"]
        CCS["ContentCreationService<br/>resolve → LLM → filter → sink"]
        FILT["ContentFilter   NoSurroundingCodeBlock<br/>update-required controller"]
    end
    subgraph prompt ["prompt package — Handlebars helpers"]
        PRS["PromptResolvingService<br/>registers all TemplateResolvers as helpers"]
        GR["gr → GRPromptEnrichment"]
        SD["source-dump   source-skeleton<br/>(+ VisualBasic skeletonizer)"]
        GRO["groovy → ScriptCompileService"]
        URL["url   current-file resolvers"]
        MCP["mcp-project resolver<br/>(project context for MCP tools)"]
    end
    subgraph io ["io package"]
        SINK["OutputSink / FileOutputSink<br/>+ OutputSinkFactory"]
    end
    LLM["KIChatProvider (prjxp-common)"]

    MAIN --> ARGS --> RUN
    RUN --> cfg & content
    CCS --> prompt & LLM & io
```

## 5.3 Level 3 — Selected Deep Dives

### 5.3.1 `PxChunk` (prjxp-common) — the universal data contract

```java
class PxChunk {
    String id;              // logical identity — shared by all parts of a split unit
    String mimeType;        // text/x-java-code, text/x-typescript-code, …
    String file;            // rootDir-relative path with leading slash (/src/Foo.java)
    String parent;          // id of the containing unit (tree edge)
    int part, total;        // split position / number of parts
    String fromLine, toLine;// 0-based line coordinates in the original file
    int size, overlap;      // content length / splitter overlap used
    String embeddingPrefix; // prepended to embedded text (e.g. "pkg ClassName")
    String project;         // owning project — stamped at embed time (multi-project)
    Map<String,String> metadata;  // pxchunk_* system keys + typed unit roles (java_code_section, symbol_*)
    String content;         // the actual text fragment
}
```

Static helpers: `create(Consumer…)` (fluent builder), `fromContentAndMap` /
`metadataAsMap` (flat metadata ↔ object mapping for JSONL/Lucene), `combine(List)`
(sort by part, overlap-aware unsplit → original unit).

### 5.3.2 `LuceneEmbeddingStore.search` — the retrieval core path

1. Embed question (or receive query embedding) → `KnnFloatVectorQuery` on the vector field.
2. Optional metadata filter (LangChain4j `Filter` → Lucene `BooleanQuery`, via
   `LuceneFilterConverter`).
3. `TopDocs` → stored fields → `TextSegment`s with metadata → `EmbeddingSearchResult`.
4. Concurrency: lazy open under write lock; searches via `SearcherManager` (shared,
   released after use); stale zero-byte `write.lock` is deleted and retried once.

### 5.3.3 `GRPromptEnrichment` — the fallback loop

```
do:
    hits = dao.searchVector(prompt, window=max(20,50)) filtered by minScore
    context = Σ over retrievers: buildPromptForFindings(hits)   // global budget 60k
    valid = contextValidators(context)                          // veto system
while !valid:
    if maxResult < 16: maxResult += 2
    else: minScore = floor((minScore - ε) / 0.05) * 0.05       // grid snap
    if minScore < 0.5: abort → "Es konnte kein valider Kontext erstellt werden"
```

The effective threshold + fallback round count is reported to the caller (transparency).

### 5.3.4 Hub pipeline worker (mcp-server)

Single daemon thread `prjxp-pipeline` (FIFO). Per project:
`register (IMPORTING) → ChunkProcess.executeForProject → scoped reset + EmbeddingService.executeForProject → READY`
(or `FAILED`). Enqueue is idempotent per name; a re-import *supersedes* the in-flight run.
On startup, projects whose chunks are already in the index go straight to `READY` (self-heal).
