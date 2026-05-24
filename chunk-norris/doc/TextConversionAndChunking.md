# Chunk Norris – Document Conversion Mechanism  

## Overview  
Chunk Norris provides a flexible, cost‑aware document conversion framework. The core of the mechanism is the `DocConversionRouter`, which discovers and executes the cheapest conversion path between any two document artifact types (`DocArtifaktType`).  

The router builds a directed weighted graph of **conversion agents** (implementations of `DocConversionAgent`).  
It then uses Dijkstra’s shortest‑path algorithm to pick the optimal sequence of agents, taking into account conversion costs, quantity estimates and required accuracy.  

---

## Key Concepts  

| Concept | Description |
|--------|-------------|
| **DocArtifakt** | Represents a document artifact. Holds the original file, its current format (`DocArtifaktType`), child artifacts and meta data needed for cost estimation. |
| **DocConversionAgent** | A Spring bean that can convert from one `DocArtifaktType` to another. It provides: <br>• `getSourceFormat()`<br>• `getTargetFormat()`<br>• `estimateCosts(DocArtifakt)`<br>• `estimateQuantity(DocArtifakt)`<br>• `accuracy()` (a `ConversionAccuracy`) |
| **ConversionRoutesConfig** | Optional configuration that defines *pre‑defined* conversion routes (hard‑coded agent sequences) for specific source/target type pairs. |
| **ConversionAccuracy** | Enum‑like model describing required accuracy level (`HIGH`, `MEDIUM`, `LOW`). Each agent reports its own accuracy rank. |
| **inaccurateSurcharge** | Global penalty (default 1 000 000) added to the edge weight when an agent’s accuracy is lower than the required one. |
| **DocContentCollector** | Utility that extracts the final textual content from a converted artifact tree. |

---

## How It Works  

### 1. Router Construction  
```java
public DocConversionRouter(List<DocConversionAgent> agents,
                         BeanNameFinder beanNameFinder,
                         ConversionRoutesConfig conversionRoutesConfig) {
    this.agents = agents;
    this.beanNameFinder = beanNameFinder;
    this.conversionRoutesConfig = conversionRoutesConfig;
    // log all available agents
}
```
*All discovered `DocConversionAgent` beans are injected by Spring.*  

### 2. Build the Conversion Graph  
```java
private Graph<DocArtifaktType, DocConversionAgent<?, ?>> buildGraph() {
    SimpleDirectedWeightedGraph<DocArtifaktType, DocConversionAgent<?, ?>> graph =
        new SimpleDirectedWeightedGraph<>(DocConversionAgent.class);
    for (var agent : agents) {
        graph.addVertex(agent.getSourceFormat());
        graph.addVertex(agent.getTargetFormat());
        graph.addEdge(agent.getSourceFormat(),
                      agent.getTargetFormat(),
                      agent);
    }
    return graph;
}
```
*Each edge represents a possible conversion step. Edge weights are set later by `estimateConversionCosts`.*

### 3. Find the Best Path  

1. **Pre‑defined route check** – If `ConversionRoutesConfig` contains a static list of agents for the requested source/target pair, that route is used directly.  

2. **Dynamic path search** –  
   - Build the graph (`buildGraph`).  
   - Recursively call `estimateConversionCosts` to assign a weight (cost) to every edge.  
   - Run Dijkstra on the weighted graph:  

```java
DijkstraShortestPath<DocArtifaktType, DocConversionAgent<?, ?>> dijkstra =
        new DijkstraShortestPath<>(conversionGraph);
GraphPath<DocArtifaktType, DocConversionAgent<?, ?>> path = dijkstra.getPath(start, end);
```

The returned list of agents is the cheapest conversion sequence.  

### 4. Cost Estimation (`estimateConversionCosts`)  

```java
var costs   = agent.estimateCosts(f);          // monetary/CPU cost estimate
var quantity = agent.estimateQuantity(f);       // how many child artifacts are produced
var total   = costs * quantity;

if (agent.accuracy().getRank() < requestedAccuracy.getRank()) {
    total += inaccurateSurcharge; // penalize low‑accuracy steps
}
conversionGraph.setEdgeWeight(agent, total);
```

The method recurses through possible next steps to fill the entire graph with realistic weights.  

### 5. Execute Conversion  

```java
List<DocConversionAgent<?, ?>> agents = findBestPath(f, start, end, accuracy);
DocArtifakt root = DocArtifakt.createRoot(f).setFormat(start);
doConversion(root, agents);          // run each agent in order
return DocContentCollector.collectTextContent(root, end);
```

*`doConversion` walks the agent list and calls `agent.convert(artifakt)`. For each generated child artifact, the same agent list is applied recursively.*  

### 6. Optional Collector Variant  
```java
public void doConversion(File f,
                         DocArtifaktType start,
                         DocArtifaktType end,
                         ConversionAccuracy accuracy,
                         Consumer<DocArtifakt> artifaktCollector) {
    // same path discovery, then:
    DocContentCollector.collectContent(root, end, artifaktCollector);
}
```
Allows callers to receive the final `DocArtifakt` tree instead of just a string.

---

## Configuration  

| Property | Default | Description |
|---------|--------|-------------|
| `prjxp.conversion.inaccurateSurcharge` | `1000000.0` | Penalty added when an agent’s accuracy is lower than required. |
| `ConversionRoutesConfig` bean | – | Holds user‑defined static routes (`List<DocConversionAgent>` per start/end pair). |

Add a `conversion-routes.yml` (or equivalent) to define shortcuts, e.g.:

```yaml
routes:
  - from: PDF
    to: HTML
    agents:
      - pdfToImageAgent
      - imageToTextAgent
```

The router will prefer this predefined list over the Dijkstra search.  

---

## Extending the System  

1. **Create a new `DocConversionAgent`**  
   - Implement `convert(DocArtifakt)`.  
   - Provide realistic implementations for `estimateCosts`, `estimateQuantity` and `accuracy()`.  
   - Register it as a Spring bean (or manually add to the router’s constructor list).  

2. **Add custom accuracy levels** – Extend `ConversionAccuracy` enum or class with additional ranks and adjust the surcharge logic if needed.  

3. **Tune cost model** – Override `inaccurateSurcharge` via Spring property or inject a custom bean that calculates dynamic penalties.  

---

## Example Usage (Spring‑Boot)  

```java
@Service
public class MyConversionService {
    private final DocConversionRouter router;

    public MyConversionService(DocConversionRouter router) {
        this.router = router;
    }

    public String convertPdfToHtml(File pdfFile) {
        return router.doConversion(
                pdfFile,
                DocArtifaktType.PDF,
                DocArtifaktType.HTML,
                ConversionAccuracy.HIGH);
    }
}
```

The service will automatically pick the cheapest route (e.g., `PdfToTextAgent → TextToHtmlAgent`) respecting accuracy constraints.

---

## Summary  

* **Graph‑based routing** – All agents form a directed weighted graph.  
* **Cost‑aware Dijkstra search** – Finds the cheapest conversion chain, penalising insufficient accuracy.  
* **Pre‑defined routes** – Override dynamic search for known fast paths.  
* **Extensible design** – Add agents, tune costs, and plug custom accuracy levels without touching the router logic.  

The `DocConversionRouter` together with its supporting model classes provides a powerful, pluggable engine for arbitrary document format conversions inside the Chunk Norris project.  

_This document was generated with Doc|Pipe and gemini-3-flash-preview_

