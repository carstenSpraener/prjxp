# 3. Context and Scope

## 3.1 Business (Functional) Context

prjxp sits between a **software project** and the **AI coding assistants** that work on it.
It is a *knowledge provider*: it ingests the project's source code and documents, and serves
retrieved knowledge to LLM clients.

```mermaid
flowchart LR
    subgraph human ["Human world"]
        DEV["Developer"]
    end

    subgraph ai ["AI clients (MCP)"]
        OC["opencode"]
        CP["GitHub Copilot<br/>VS Code / JetBrains"]
    end

    subgraph prjxp ["prjxp (this system)"]
        PIPE["Chunking + Embedding pipeline"]
        SRV["MCP Server / REST API<br/>+ Hub runtime"]
    end

    subgraph ext ["External services"]
        EMB[("Embedding provider<br/>LM Studio / Ollama / TEI")]
        LLM[("Chat LLMs<br/>Gemini, local models")]
    end

    subgraph data ["Data"]
        PROJ[("files on disk / tar archive")]
    end

    DEV -->|"asks questions"| OC & CP
    OC & CP <-->|"MCP streamable HTTP / REST<br/>vectorSearch, grep, readFile"| SRV
    PROJ -->|"walked & chunked"| PIPE
    PIPE <-->|"embed calls HTTP"| EMB
    SRV -->|"retrieval from index"| PIPE
    LLM <-->|"docpipe / questioner HTTP"| SRV
```

**External functional interfaces:**

| Interface | Direction | Description |
|---|---|---|
| MCP tools (`/mcp`, streamable HTTP) | AI client → prjxp | `vectorSearch` (semantic discovery), `grep` (exact full-text), `readFile` / `readBySignature` (complete source views), `listProjects`. |
| REST API (`/prjxp/tools/*`, `/prjxp/projects*`) | humans/scripts → prjxp | Same capabilities as plain HTTP (ping, context, vectorSearch, grep, byIndex, meta-search-params) plus hub project lifecycle (list/delete/reindex). |
| Web UI (`/`) | humans → prjxp | Hub project list with lifecycle status (static `index.html` + vanilla JS). |
| Embedding API (OpenAI-compatible `/v1/embeddings`, Ollama) | prjxp → external | Batched embedding of chunk content. |
| Chat LLM API (OpenAI-compatible, Gemini, …) | prjxp → external | Used by docpipe and the golden-retriever questioner. |
| File system / tar archives | external → prjxp | The indexed project: mounted directory (single mode) or dropped tar / live marker dir (hub). |

## 3.2 Technical Context

```mermaid
flowchart LR
    subgraph host ["Host machine"]
        subgraph docker ["prjxp image (single or hub)"]
            CN["chunk-norris-all.jar"]
            TIB["tibed-all.jar"]
            MS["mcp-server-all.jar<br/>Tomcat :7007/:7008"]
            ENTRY["entry.sh<br/>chunk embed serve hub"]
        end
        V1[("Volume: project source<br/>/app-source")]
        V2[("Volume: Lucene index<br/>.prjxp-data/lucene-index")]
        V3[("Hub volumes:<br/>/import /projects /data")]
        HOSTEMB["External embedding server<br/>on host, e.g. LM Studio :1234"]
    end

    ENTRY --> CN & TIB & MS
    V1 --- CN
    V2 --- TIB & MS
    V3 --- MS
    MS <-->|"HTTP host.docker.internal"| HOSTEMB
```

**External technical interfaces:**

| Interface | Technology | Notes |
|---|---|---|
| MCP endpoint `POST /mcp` | Streamable HTTP (Spring AI MCP server WebMVC, protocol `streamable`, name `prjxp-mcp` v1.0.0) | CORS restricted to LAN/localhost patterns; a compatibility filter rewrites legacy protocol version `2025-06-18` requests. |
| REST endpoints | Spring MVC + springdoc OpenAPI (Swagger UI) | `/prjxp/tools/ping|context|vectorSearch|grep|byIndex|meta-search-params`, `/prjxp/projects[/{name}[/reindex]]`. |
| Embedding provider | HTTP (OpenAI-compatible or Ollama) | Configured via `prjxp.embedding.*`; in Docker typically `http://host.docker.internal:1234/v1`. |
| File system | Bind mounts / named volumes | Project source, Lucene index directory, hub import/projects/data areas. |
| JSONL files | Plain files / stdout | `px-chunks.jsonl` (chunk output) and optional encrypted transfer files. |

**Mapping functional → technical interfaces:**

| Functional interface | Technical realization |
|---|---|
| "Ask the project expert" | MCP `vectorSearch` → Lucene KNN search + golden-retriever enrichment |
| "Find this exact string" | MCP `grep` → Lucene full-text (`TermQuery`) over chunk content + metadata filters |
| "Show me the whole file / method" | MCP `readFile`/`readBySignature` → index reconstruction via per-language `FileViewProvider`s |
| "Index my project" | chunk-norris (walk + vetoes + chunkers → JSONL) then tibed (embed → Lucene) |
| "Add a project to the hub" | tar drop into `/import` or `prjxp.yaml` marker in a live dir → in-process pipeline |

## 3.3 Scope

### In scope

- **Code languages:** Java (full: methods, javadoc, class frames, symbol metadata),
  TypeScript, Visual Basic (code sections + file views; no `symbol_*` metadata yet).
- **Document formats:** Markdown, plain text, PDF (chapter chunking + Tika conversion),
  DOCX/DOC, HTML, RTF — via the doc-conversion router (agents per format).
- **Interfaces:** MCP streamable HTTP, REST API, CLI (`chunk`/`embed` modes), hub web UI.
- **Deployment:** single-project container (one index per project) and multi-project hub
  (shared, project-stamped Lucene index).
- **Multi-project addressing:** every chunk is stamped with its `project`; all searches are
  project-scoped.

### Out of scope

- General-purpose vector database or search engine for arbitrary (non-project) data.
- IDE plugin / editor integration — prjxp integrates *through* existing MCP clients.
- Binary formats and images as searchable content (images are vetoed by default; an
  image→Markdown conversion agent exists for document pipelines).
- Model training, fine-tuning or local inference — prjxp only *consumes* model APIs.
- Source control integration (no git-aware incremental indexing; re-indexing is explicit).
