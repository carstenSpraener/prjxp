# 2. Architecture Constraints

## 2.1 Platform and Language

| Constraint | Value / Source |
|---|---|
| Language & runtime | **Java 21** (CI: Temurin JDK 21; Docker: `eclipse-temurin:21-jre-jammy`) |
| Framework | **Spring Boot 3.5.x** — used as *starter dependencies only*; the Spring Boot Gradle plugin is **not** applied in modules (see 2.4) |
| Build | Gradle multi-project build, version `0.0.1-SNAPSHOT`, group `de.spraener.prjxp` |
| AI stack | LangChain4j 1.13.x (core, Ollama, OpenAI-compatible, Gemini, MCP client), Spring AI 1.1.x (MCP server WebMVC) |
| Index engine | Apache Lucene 9.12 (KNN float vectors + full-text) — **the only** storage backend |

## 2.2 Dependency Management

- All dependencies are declared **exclusively** in the version catalog
  `gradle/libs.versions.toml` (libraries, plugins and *bundles* such as
  `langchain-stack`, `test-bundle`, `conversion`). **No ad-hoc version strings** in
  sub-project `build.gradle` files (a few explicit exceptions exist, e.g.
  `langchain4j-http-client-jdk`, Handlebars — see [11](11_risks_and_technical_debt.md)).
- Bundles keep module build files short: `langchain-stack` (core + ollama + gemini + openai
  + mcp), `langchain-docparser` (Tika, PDFBox), `conversion` (PDFBox, XDocReport, Flexmark,
  Jsoup), `test-bundle` (JUnit via starter-test + Mockito + AssertJ).

## 2.3 Testing Constraints

- JUnit 5 + Mockito + AssertJ; tests run with `-Djava.awt.headless=false` (set globally in
  the root `build.gradle`) because PDF/document processing touches AWT.
- **JaCoCo coverage ratchets** per module (line covered ratio, verified on `check`):

  | Module | Ratchet |
  |---|---|
  | prjxp-common | 0.50 |
  | chunk-norris | 0.70 |
  | tibed | 0.55 |
  | golden-retriever | 0.35 |
  | lucene-store | 0.75 |
  | docpipe | 0.30 |
  | mcp-server | 0.80 (strict — "DockerHub code lands here") |

  Ratchets may only be **raised** (after legacy code got tested), never lowered.
- mcp-server tests run with `prjxp.cli.enabled=false` so that the chunk-norris/tibed
  `CommandLineRunner`s on the hub classpath do not fire during tests.

## 2.4 Packaging Constraint: Shadow JAR, Not Boot Repackage

Modules that are executable (`chunk-norris`, `tibed`, `mcp-server`, `docpipe`) use the
`application` + **shadow** plugins. The Spring Boot Gradle plugin is deliberately *not*
applied, so the fat JAR must merge Spring's metadata files itself:

- `mergeServiceFiles()` (Java SPI — critical for chunker discovery)
- append/merge of `META-INF/spring.handlers`, `spring.schemas`, `spring.tooling`,
  `AutoConfiguration.imports` and the actuator management imports
- `PropertiesFileTransformer` (append) for `META-INF/spring.factories`

> **Rule:** when adding a new Spring auto-configuration, verify the `shadowJar` transform
> block of every module that ships it (AGENTS.md).

Additional packaging rules:

- `application.yaml`/`application.yml` are **excluded** from fat JARs (config comes from
  the deployment environment; in Docker, `application.yaml.docker` is baked in as fallback).
- **Spring DevTools must never end up in a fat JAR** (it would swallow error exit codes).
- `Multi-Release: true` manifest attribute; `zip64=true`.

## 2.5 Storage Constraint

- **Lucene is the only vector store.** ChromaDB and MySQL support were removed
  (commit "Lucene as the only storage"). The index is a plain directory
  (`LUCENE_INDEX_PATH`, default `.prjxp-data/lucene-index`) — no database service,
  no network dependency for storage.
- Consequence: exactly **one writer** per index at a time (Lucene write lock). All
  multi-project pipeline execution is serialized accordingly (see [06](06_runtime_view.md)).
- The vector dimension is fixed per index (`LUCENE_VECTOR_DIMENSION`, default 1024,
  matching `mxbai-embed-large-v1`). Changing the embedding model invalidates existing data.

## 2.6 AI Model Constraint

- The application **never runs neural inference itself**. Embedding and chat models are
  external HTTP services: OpenAI-compatible endpoints (LM Studio, TEI, …) or Ollama.
  Model selection is configuration-driven (`prjxp.embedding.*`, `prjxp.chat-models[]`).
- In Docker, an embedded **TEI** (text-embeddings-router) CPU server *can* be started by
  `entry.sh`, but the current compose setup uses an external provider on the host
  (`SKIP_EMBEDDING_SERVER=true`).

## 2.7 Configuration & Secrets

- Runtime configuration: `application.yaml` (Spring) + `.env` file loaded via
  **dotenv-java** into system properties before Spring starts (`PrjXPCli.readDotEnv`).
- Per-project configuration: `prjxp.yaml` marker file (name, whitelist, batch size, reset).
- **Never commit real secrets** — `.env.example` is the template; CI injects keys as
  GitHub Actions secrets.

## 2.8 Deployment Platform Constraint

- The Docker image is built for **linux/amd64** (the embedded TEI server only ships an
  x86_64 build). Apple Silicon runs it via Rosetta (expect embedding throughput loss).
- The image is self-contained: three fat JARs + `entry.sh`; all state lives in volumes.
