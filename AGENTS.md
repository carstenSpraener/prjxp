# AGENTS.md

## Build & Test Commands

- Full build: `./gradlew build`
- Single module test: `./gradlew :chunk-norris:test`
- Run app: `./gradlew :chunk-norris:run` or `:tibed:run`, `:golden-retriever:run`, `:mcp-server:run`, `:docpipe:run`
- Shadow JAR (fat jar): `./gradlew :chunk-norris:shadowJar`
- No separate lint/typecheck tasks; compilation errors surface during `build`.

## Architecture

Gradle multi-project with version catalog (`gradle/libs.versions.toml`). Modules:

- **prjxp-common** – shared `PxChunk` data model and utilities; dependency of all other modules.
- **chunk-norris** – CLI chunking framework; outputs JSONL. Extensible via Java SPI (`META-INF/services/de.spraener.chuno.ChunkerBroker`). Main class: `de.spraener.prjxp.chuno.ChunkNorris`.
- **tibed** – batch embedding engine; reads chunk-norris JSONL, writes to ChromaDB.
- **golden-retriever** – RAG engine with `JavaCodeRetriever`, forest-of-trees enrichment, veto system.
- **mcp-server** – Spring AI MCP server (WebMVC).
- **docpipe** – document processing pipeline.
- **encubator/oragel** – experimental/incubator module (not in default `settings.gradle` includes).

All modules use Spring Boot 3.5.x, Lombok, LangChain4j, JUnit 5 + Mockito + AssertJ.

## Conventions

- Dependencies managed exclusively via `gradle/libs.versions.toml` bundles (`langchain-stack`, `test-bundle`, `conversion`, etc.). Never add ad-hoc version strings in subproject `build.gradle`.
- Tests require `-Djava.awt.headless=false` (set globally in root `build.gradle`).
- Chunk-Norris shadow JAR merges Spring metadata files; if adding new Spring auto-configurations, verify the `shadowJar` transform block in `chunk-norris/build.gradle`.
- Zero manual Javadoc by design; documentation is generated via the tool's own retrieval pipeline.
- `.env` file at root for runtime secrets (dotenv-java loads it); never commit real values.

</content>