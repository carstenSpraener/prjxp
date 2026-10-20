# Document Conversion Mechanism – Chunk Norris

## Overview

The document conversion system in Chunk Norris is a **graph-based routing engine** that transforms a document from one format (e.g. PDF) to another (e.g. Markdown) by chaining together individual **conversion agents**. The router selects the most cost-efficient chain of agents and then executes the conversion recursively over the document's artifact tree.

All core classes live in the package `de.spraener.prjxp.chuno.docs`.

---

## Key Concepts

| Term | Description |
|---|---|
| **`DocArtifaktType`** | An enum representing a document format (PDF, HTML, MARK_DOWN, TEXT, DOC, …). Acts as a **vertex** in the conversion graph. |
| **`DocConversionAgent<S, T>`** | A Spring bean that knows how to convert from source format `S` to target format `T`. Acts as a **directed edge** in the graph. |
| **`DocArtifakt`** | The in-memory representation of a document (or a sub-document). Carries a `format`, a `data` payload, and an optional list of **child artifacts**. |
| **`ConversionAccuracy`** | An enum (ordinal-ordered) expressing how lossy a conversion may be. |
| **`ConversionRoutesConfig`** | Optional YAML/properties configuration for **predefined routes** that bypass the graph search. |
| **`DocContentCollector`** | Utility that walks the final artifact tree and extracts the text/structured content in the target format. |

---

## The Conversion Graph

```
        ┌──────────┐   agent-pdf2html   ┌──────────┐   agent-html2md   ┌────────────┐
        │   PDF    │ ─────────────────► │   HTML   │ ─────────────────► │ MARK_DOWN  │
        └──────────┘                    └──────────┘                    └────────────┘
             │                                  ▲
             │ agent-pdf2text                  │ agent-text2html
             ▼                                  │
        ┌──────────┐                           │
        │   TEXT   │ ──────────────────────────┘
        └──────────┘
```

* **Vertices** – every `DocArtifaktType` that appears as a source or target of at least one agent.
* **Directed edges** – one per `DocConversionAgent` bean. The edge weight is the **estimated conversion cost** (see below).
* The graph is built at runtime by `DocConversionRouter#buildGraph()` using JGraphT's `SimpleDirectedWeightedGraph`.

---

## Path Selection – `findBestPath`

The method `DocConversionRouter#findBestPath(File, DocArtifaktType, DocArtifaktType, ConversionAccuracy)` determines the ordered list of agents to apply. It follows a two-tier strategy:

1. **Predefined route (fast path).**
   `ConversionRoutesConfig#findPredefinedRouteAgents(start, end)` is consulted first. If the configuration supplies an explicit agent chain for the given format pair, that chain is returned immediately and the graph is never built.

2. **Dijkstra shortest path (general path).**
   If no predefined route exists, the router:
   1. Builds the conversion graph from all registered `DocConversionAgent` beans.
   2. Calls `estimateConversionCosts(…)` to assign a **weight** to every edge (see *Cost Estimation* below).
   3. Runs `DijkstraShortestPath` from `start` to `end`.
   4. Returns the edge list of the resulting `GraphPath`, or an empty list if no path exists.

If `start == end` the method short-circuits and returns an empty list (no conversion needed).

---

## Cost Estimation – `estimateConversionCosts`

This private recursive method walks the graph **from the source format toward the target format**, assigning a weight to each edge it encounters.

For every agent whose `sourceFormat` matches the current artifact's format (and that has not yet been visited in this branch):

```
totalCosts = agent.estimateCosts(artifakt)  ×  agent.estimateQuantity(artifakt)
```

* `estimateCosts` – a per-unit cost (e.g. CPU time, API call cost) the agent reports for the given artifact.
* `estimateQuantity` – a multiplier reflecting the artifact's size/complexity (e.g. number of pages, number of child elements).

**Accuracy surcharge.** If the agent's own `accuracy()` rank is *lower* (more lossy) than the `ConversionAccuracy` requested by the caller, a configurable penalty is added:

```
totalCosts += inaccurateSurcharge   // default: 1 000 000.0
```

The value is read from the property `prjxp.conversion.inaccurateSurcharge`. This makes Dijkstra strongly prefer routes that meet the requested accuracy, while still allowing a lossy route if it is the *only* path.

The method then creates a **synthetic child artifact** (same content, format changed to the agent's target, quantity propagated) and recurses. A `visited` set prevents infinite cycles in the graph.

---

## Execution – `doConversion`

Two public overloads exist:

| Signature | Returns |
|---|---|
| `doConversion(File, start, end, accuracy)` | `String` – the final text content |
| `doConversion(File, start, end, accuracy, Consumer<DocArtifakt>)` | `void` – streams each artifact to the consumer |

Both follow the same pipeline:

```
┌─────────────────────────────────────────────────────────────────────┐
│  1. findBestPath(…)  →  List<DocConversionAgent>                   │
│  2. DocArtifakt.createRoot(file)  →  root artifact                 │
│  3. root.setFormat(start)                                            │
│  4. loadTextContent(root, file, start)                              │
│       • text-based formats (HTML, TEXT, MARK_DOWN):                 │
│         read the file into a UTF-8 String → root.setData(…)         │
│       • binary formats (PDF, DOC, …):                               │
│         no-op – the File reference stays on disk                    │
│  5. doConversion(root, agents)   ← recursive agent application      │
│  6. DocContentCollector.collectTextContent / collectContent          │
└─────────────────────────────────────────────────────────────────────┘
```

### Recursive agent application

The private `doConversion(DocArtifakt, List<DocConversionAgent>)` method:

1. Takes the **first** agent in the list and calls `agent.convert(artifakt)`.
   The agent mutates the artifact in place (changes its format, rewrites its data, and may produce **child artifacts**).
2. Recurses into every child artifact with the **remaining** agents.
3. If the agent list is empty, the recursion stops.

This design means a single top-level document can fan out into a tree of sub-artifacts (e.g. a PDF with multiple pages), and each branch of the tree is converted independently through the same agent chain.

---

## Text vs. Binary Handling

```java
private static boolean isTextBasedFormat(DocArtifaktType type) {
    return type == DocArtifaktType.HTML
            || type == DocArtifaktType.TEXT
            || type == DocArtifaktType.MARK_DOWN;
}
```

* **Text-based formats** are held as a `String` in `DocArtifakt.data`. The router reads the file into memory before the first agent runs, because agents and the content collector expect a `String` payload.
* **Binary formats** (PDF, DOC, …) keep the `File` reference on disk; agents read/write the file directly.

---

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `prjxp.conversion.inaccurateSurcharge` | `1000000.0` | Cost penalty added to any agent whose accuracy is below the requested level. Effectively makes lossy routes "very expensive" so Dijkstra avoids them unless no accurate path exists. |

Predefined routes are configured via `ConversionRoutesConfig` (Spring `@ConfigurationProperties`). Each entry maps a `(sourceFormat, targetFormat)` pair to an ordered list of agent bean names, bypassing the graph search entirely.

---

## Extending the System

To add a new conversion capability:

1. Create a new `DocArtifaktType` enum constant if the format does not yet exist.
2. Implement a `DocConversionAgent<Source, Target>` bean:
   * `getSourceFormat()` / `getTargetFormat()` – the edge endpoints.
   * `convert(DocArtifakt)` – perform the actual transformation.
   * `estimateCosts(DocArtifakt)` – return a per-unit cost.
   * `estimateQuantity(DocArtifakt)` – return a size/complexity multiplier.
   * `accuracy()` – declare the `ConversionAccuracy` this agent achieves.
3. Register the bean with Spring. The `DocConversionRouter` picks it up automatically via constructor injection of `List<DocConversionAgent>`.
4. (Optional) Add a predefined route in `ConversionRoutesConfig` if you want to force a specific chain for a format pair.

No changes to the router itself are required.

---

## Class Diagram (simplified)

```
DocConversionRouter
 ├── List<DocConversionAgent>          (all registered agents)
 ├── BeanNameFinder                    (Spring bean-name lookup for logging)
 ├── ConversionRoutesConfig            (predefined routes)
 ├── double inaccurateSurcharge        (configurable penalty)
 │
 ├── buildGraph()                      → SimpleDirectedWeightedGraph
 ├── findBestPath(…)                  → List<DocConversionAgent>
 │    ├── ConversionRoutesConfig       (fast path)
 │    ├── estimateConversionCosts(…)   (weight assignment)
 │    └── DijkstraShortestPath         (shortest path)
 ├── doConversion(…)                  → String | void
 │    ├── loadTextContent(…)
 │    ├── doConversion(artifakt, agents)   (recursive)
 │    └── DocContentCollector
 └── listAgentPath(…)                 (logging helper)
```

_This document was generated with Doc|Pipe and gemini-3-flash-preview_

