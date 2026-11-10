# Document Conversion Mechanism

## Overview

The document conversion system in chunk norris transforms documents from one format to another by routing them through a chain of **conversion agents**. The routing is solved as a **shortest-path problem** on a directed, weighted graph where:

- **Vertices** are document formats (`DocArtifaktType`)
- **Edges** are conversion agents (`DocConversionAgent`)
- **Edge weights** are estimated conversion costs

The central orchestrator is `DocConversionRouter`, a Spring `@Service` that builds the graph, computes the optimal path, and executes the conversion pipeline.

---

## Core Components

| Component | Role |
|-----------|------|
| `DocConversionRouter` | Orchestrates path-finding and conversion execution |
| `DocConversionAgent<S, T>` | A single conversion step (source format → target format) |
| `DocArtifakt` | In-memory representation of a document (tree of nodes) |
| `DocArtifaktType` | Enum of supported document formats (PDF, HTML, MARK_DOWN, TEXT, DOC, …) |
| `ConversionAccuracy` | Enum ranking the fidelity of a conversion (e.g. LOSSY < LOSSLESS) |
| `ConversionRoutesConfig` | Optional predefined routes that bypass graph search |
| `DocContentCollector` | Extracts the final text/content from the converted artifact tree |
| `BeanNameFinder` | Resolves Spring bean names for logging |

---

## Conversion Graph

The router builds a `SimpleDirectedWeightedGraph<DocArtifaktType, DocConversionAgent<?,?>>` (JGraphT) at query time:

```
for each registered DocConversionAgent:
    addVertex(agent.sourceFormat)
    addVertex(agent.targetFormat)
    addEdge(source, target, agent)
```

Every agent registered as a Spring bean is automatically picked up via constructor injection (`List<DocConversionAgent>`). At startup the router logs all known agents and their format pairs.

---

## Path Selection (`findBestPath`)

The method `findBestPath(File, DocArtifaktType start, DocArtifaktType end, ConversionAccuracy)` resolves the agent chain in three steps:

1. **Identity check** – if `start == end`, an empty list is returned (no conversion needed).
2. **Predefined routes** – `ConversionRoutesConfig` may supply a hand-crafted agent list for a given (start, end) pair. If present, it is used directly, skipping the graph search.
3. **Dijkstra shortest path** – the graph is built, edge weights are estimated (see below), and `DijkstraShortestPath` computes the minimum-cost path from `start` to `end`. If no path exists, an empty list is returned.

---

## Cost Estimation

Before Dijkstra runs, the router performs a **recursive walk** (`estimateConversionCosts`) over the conversion graph to assign realistic edge weights:

```
estimateConversionCosts(graph, artifakt, targetFormat, requiredAccuracy, visitedAgents):
    if artifakt.format == targetFormat: return
    for each agent where agent.sourceFormat == artifakt.format and agent ∉ visited:
        costs    = agent.estimateCosts(artifakt)
        quantity = agent.estimateQuantity(artifakt)
        total    = costs × quantity

        if agent.accuracy().rank < requiredAccuracy.rank:
            total += inaccurateSurcharge   // configurable penalty

        graph.setEdgeWeight(agent, total)

        next = artifakt.copy()
                 .setFormat(agent.targetFormat)
                 .setChildQuantityEstimation(quantity)
        recurse(graph, next, targetFormat, requiredAccuracy, visited ∪ {agent})
```

Key points:

- **`estimateCosts`** – per-unit cost (e.g. CPU time, memory) for converting one element.
- **`estimateQuantity`** – how many elements the agent will encounter (e.g. number of pages, number of child nodes).
- **Accuracy surcharge** – if an agent's inherent accuracy rank is *lower* than the caller's required accuracy, a large penalty (`prjxp.conversion.inaccurateSurcharge`, default `1 000 000.0`) is added, effectively discouraging lossy hops unless no better route exists.
- **Cycle prevention** – a `visited` set of agents is threaded through the recursion so the same agent is never traversed twice in a single path.

---

## Conversion Execution (`doConversion`)

Once the agent list is determined, the actual conversion is a **recursive tree walk**:

```
doConversion(artifakt, agents):
    if agents is empty: return
    agent = agents[0]
    agent.convert(artifakt)          // mutates / replaces the artifakt in place
    remaining = agents[1..]
    for each child in artifakt.children:
        doConversion(child, remaining)
```

- The **root** `DocArtifakt` is created from the source `File` and tagged with the start format.
- For **text-based formats** (HTML, TEXT, MARK_DOWN) the file content is read into the artifact's `data` field as a `String` before the first agent runs. Binary formats (PDF, DOC, …) keep the `File` reference on disk.
- Each agent transforms the artifact (and its children) in place, potentially changing the format and the internal data representation.
- After the full chain has been applied, `DocContentCollector` extracts the final content in the target format.

Two public entry points exist:

| Method | Returns |
|--------|---------|
| `doConversion(File, start, end, accuracy)` | `String` – the collected text content |
| `doConversion(File, start, end, accuracy, Consumer<DocArtifakt>)` | `void` – streams each artifact to the supplied collector (useful for binary or structured output) |

---

## Configuration

| Property | Default | Purpose |
|----------|---------|---------|
| `prjxp.conversion.inaccurateSurcharge` | `1000000.0` | Cost penalty added to edges whose agent accuracy is below the required level |
| `ConversionRoutesConfig` (bean) | – | Optional map of `(start, end) → List<DocConversionAgent>` for hand-tuned routes |

---

## Example Flow

Converting a **PDF** to **MARK_DOWN** with `ConversionAccuracy.LOSSLESS`:

```
1. findBestPath:
   - No predefined route → build graph
   - estimateConversionCosts walks:
       PDF ──[pdfToHtmlAgent]──► HTML ──[htmlToMdAgent]──► MARK_DOWN
     (weights computed from page count, element count, accuracy check)
   - Dijkstra selects: [pdfToHtmlAgent, htmlToMdAgent]

2. doConversion:
   - Root DocArtifakt created from file, format = PDF
   - pdfToHtmlAgent.convert(root)   → root is now HTML (String in memory)
   - For each child: htmlToMdAgent.convert(child)
   - DocContentCollector.collectTextContent(root, MARK_DOWN) → "final markdown"
```

---

## Extending the System

To add a new conversion capability:

1. Implement `DocConversionAgent<Source, Target>` with:
   - `sourceFormat()` / `targetFormat()`
   - `convert(DocArtifakt)` – perform the actual transformation
   - `estimateCosts(DocArtifakt)` – return per-unit cost
   - `estimateQuantity(DocArtifakt)` – return expected element count
   - `accuracy()` – declare the fidelity level
2. Register it as a Spring `@Component` / `@Service`.
3. (Optional) Add a predefined route in `ConversionRoutesConfig` if the automatic path is suboptimal.

No changes to `DocConversionRouter` are required; the agent is discovered automatically.

_This document was generated with Doc|Pipe and gemini-3-flash-preview_

