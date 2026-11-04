# Document Conversion in Chunk Norris

This document explains the document conversion mechanism used in Chunk Norris.

The conversion system is implemented around `DocConversionRouter`. It discovers available conversion agents, builds a directed conversion graph, selects the best route from a source document format to a target format, executes the selected conversions, and finally collects the converted content.

## Overview

The document conversion mechanism converts documents between different `DocArtifaktType`s, for example:

- `PDF`
- `DOC`
- `HTML`
- `TEXT`
- `MARK_DOWN`

Conversions are performed by Spring-managed `DocConversionAgent`s. Each agent knows how to convert from exactly one source format to one target format.

Example:

```text
PDF -> HTML
HTML -> MARK_DOWN
MARK_DOWN -> TEXT
```

The router can combine multiple agents into a conversion chain if no direct conversion exists.

## Main Components

### `DocConversionRouter`

`DocConversionRouter` is the central service for document conversion.

It is responsible for:

- discovering all available `DocConversionAgent`s
- building a directed weighted graph of possible conversions
- finding the best conversion path
- applying conversion agents in sequence
- collecting the final converted content

### `DocConversionAgent`

A `DocConversionAgent` represents one conversion step.

Conceptually, an agent provides:

- source format
- target format
- conversion logic
- estimated conversion cost
- estimated output quantity
- conversion accuracy

An agent is represented as an edge in the conversion graph.

### `DocArtifakt`

`DocArtifakt` represents a document artifact during conversion.

A conversion may produce one or multiple child artifacts. Therefore, the conversion result is not just a linear value but a tree of artifacts.

Example:

```text
root.pdf
└── page-1.html
    └── page-1.md
└── page-2.html
    └── page-2.md
```

### `DocArtifaktType`

`DocArtifaktType` describes the format of an artifact.

Examples:

```text
PDF
HTML
TEXT
MARK_DOWN
```

### `ConversionAccuracy`

`ConversionAccuracy` describes the expected quality of a conversion.

When searching for a route, the router can prefer agents that meet the requested accuracy level.

If an agent has lower accuracy than requested, an additional surcharge is added to its estimated cost.

## Conversion Flow

A typical conversion flow looks like this:

```text
Input File
   |
   v
DocArtifakt root
   |
   v
Find best conversion path
   |
   v
Execute conversion agents
   |
   v
Collect target content
   |
   v
Result
```

## Route Discovery

When the application starts, Spring injects all known `DocConversionAgent`s into the `DocConversionRouter`.

The router logs all known agents:

```text
The conversion system knows the following DocConversionAgents:
   - pdfToHtmlAgent converts (from PDF to HTML)
   - htmlToMarkdownAgent converts (from HTML to MARK_DOWN)
   - markdownToTextAgent converts (from MARK_DOWN to TEXT)
```

Each agent defines one directed conversion route:

```text
SOURCE_FORMAT -> TARGET_FORMAT
```

The router uses these agents to build a directed weighted graph.

## Conversion Graph

Internally, the router builds a graph using JGraphT:

```java
Graph<DocArtifaktType, DocConversionAgent<?, ?>> conversionGraph =
    new SimpleDirectedWeightedGraph(DocConversionAgent.class);
```

In this graph:

- vertices are `DocArtifaktType`s
- edges are `DocConversionAgent`s
- edge weights are estimated conversion costs

Example graph:

```text
PDF ───────────────> HTML ───────────────> MARK_DOWN ───────────────> TEXT
 │                    │
 │                    └──────────────────> TEXT
 │
 └──────────────────> TEXT
```

Each arrow represents a `DocConversionAgent`.

## Finding the Best Path

The router uses Dijkstra's shortest path algorithm to find the cheapest route from the source format to the target format.

```java
DijkstraShortestPath<DocArtifaktType, DocConversionAgent<?, ?>> dijkstra =
    new DijkstraShortestPath<>(conversionGraph);

GraphPath<DocArtifaktType, DocConversionAgent<?, ?>> path =
    dijkstra.getPath(start, end);
```

The selected path is the list of conversion agents that should be executed.

Example:

```text
PDF -> HTML -> MARK_DOWN
```

The corresponding agent list may be:

```text
pdfToHtmlAgent
htmlToMarkdownAgent
```

## Predefined Routes

Before calculating the best route dynamically, the router checks whether a predefined route exists.

```java
List<DocConversionAgent<?, ?>> predefinedAgents =
    conversionRoutesConfig.findPredefinedRouteAgents(start, end);
```

If a predefined route is configured, it is used immediately.

Dynamic graph routing is skipped in that case.

This allows the application to force a specific route, even if another route would appear cheaper according to cost estimation.

Example:

```text
PDF -> HTML -> MARK_DOWN
```

A predefined route is useful when:

- a specific conversion chain gives better practical results
- a cheaper route is known to produce poor output
- route behavior must be deterministic
- route selection should be controlled by configuration

## Cost Estimation

If no predefined route exists, the router estimates the cost of all possible conversion steps.

For every possible agent, the router calculates:

```text
total cost = estimated cost * estimated quantity
```

The values are provided by the agent:

```java
var costs = agent.estimateCosts(f);
var quantity = agent.estimateQuantity(f);
var totalCosts = costs * quantity;
```

### Quantity Estimation

`estimateQuantity` is used to model conversions that split one artifact into many child artifacts.

For example:

```text
PDF -> HTML
```

may produce one HTML artifact per page.

If a PDF has 20 pages, the quantity estimate may be `20`.

This makes the route more realistic because subsequent conversion steps may have to process all generated children.

### Accuracy Surcharge

Each agent declares its conversion accuracy:

```java
var agentAccuracy = agent.accuracy();
```

If the agent accuracy is lower than the requested accuracy, the router adds a surcharge:

```java
if (agentAccuracy.getRank() < accuracy.getRank()) {
    totalCosts += inaccurateSurcharge;
}
```

The default surcharge is:

```properties
prjxp.conversion.inaccurateSurcharge=1000000.0
```

This does not completely forbid inaccurate conversions, but it makes them very expensive.

As a result, the router will usually prefer a more accurate route if one exists.

## Cost Example

Assume the requested accuracy is `HIGH`.

Available routes:

```text
PDF -> TEXT
PDF -> HTML -> MARK_DOWN -> TEXT
```

Estimated costs:

| Route | Agent Accuracy | Estimated Cost |
|---|---:|---:|
| `PDF -> TEXT` | low | `10 + inaccurateSurcharge` |
| `PDF -> HTML -> MARK_DOWN -> TEXT` | high | `150` |

Even though the direct conversion has a lower base cost, the surcharge makes it less attractive.

The router chooses:

```text
PDF -> HTML -> MARK_DOWN -> TEXT
```

## Artifact Tree Conversion

The router converts artifacts recursively.

First, the selected agent is applied to the current artifact:

```java
agent.convert(artifakt);
```

Then the remaining agents are applied to all child artifacts:

```java
for (var child : artifakt.getChilds()) {
    doConversion((DocArtifakt) child, remainingAgents);
}
```

This means conversions naturally support branching.

Example:

```text
PDF
└── HTML page 1
    └── Markdown page 1
└── HTML page 2
    └── Markdown page 2
└── HTML page 3
    └── Markdown page 3
```

The route is linear, but the artifact structure can be a tree.

## Text-Based and Binary Formats

The root artifact is initially created from a `File`.

For binary formats, the file remains the artifact data.

For text-based formats, the router reads the file content into memory as a `String`.

Text-based formats are:

- `HTML`
- `TEXT`
- `MARK_DOWN`

The file is read as UTF-8:

```java
Files.readString(f.toPath(), StandardCharsets.UTF_8)
```

This is necessary because text-based conversion agents and content collection expect the artifact data to be a `String`.

Binary formats such as `PDF` or `DOC` stay represented by a `File`.

## Conversion API

### Convert and Return Text

```java
public String doConversion(
    File f,
    DocArtifaktType start,
    DocArtifaktType end,
    ConversionAccuracy accuracy
)
```

This method:

1. finds the best conversion path
2. creates the root `DocArtifakt`
3. loads text content if required
4. executes all conversion agents
5. collects text content for the requested target format
6. returns the collected text as `String`

Example:

```java
String markdown = docConversionRouter.doConversion(
    new File("document.pdf"),
    DocArtifaktType.PDF,
    DocArtifaktType.MARK_DOWN,
    ConversionAccuracy.HIGH
);
```

### Convert and Collect Artifacts

```java
public void doConversion(
    File f,
    DocArtifaktType start,
    DocArtifaktType end,
    ConversionAccuracy accuracy,
    Consumer<DocArtifakt> artifaktCollector
)
```

This method performs the same conversion, but instead of returning collected text, it calls the given consumer for matching artifacts.

Example:

```java
docConversionRouter.doConversion(
    new File("document.pdf"),
    DocArtifaktType.PDF,
    DocArtifaktType.HTML,
    ConversionAccuracy.HIGH,
    artifakt -> {
        System.out.println(artifakt.getData());
    }
);
```

Use this variant when the caller needs access to individual artifacts instead of one collected text result.

## Path Logging

The selected conversion path is logged in a readable format.

Example:

```text
Selected conversion path is: pdfToHtmlAgent[PDF>>HTML] -> htmlToMarkdownAgent[HTML>>MARK_DOWN]
```

If the Spring bean name cannot be resolved, the class name of the agent is used as fallback.

## Behavior for Identical Source and Target Formats

If source and target format are identical, no conversion is necessary.

```java
if (start == end) {
    return Collections.emptyList();
}
```

The router returns an empty agent list.

The root artifact may still be loaded and collected.

## Behavior When No Route Exists

If no route can be found, the router returns an empty agent list.

```java
return (path != null) ? path.getEdgeList() : Collections.emptyList();
```

In that case, no conversion agents are executed.

Depending on the requested target format and the collector behavior, the result may be empty or only contain matching already-existing artifacts.

## Configuration

### Inaccurate Conversion Surcharge

The surcharge for agents that do not meet the requested accuracy is configurable:

```properties
prjxp.conversion.inaccurateSurcharge=1000000.0
```

Default:

```text
1000000.0
```

A higher value makes inaccurate conversions less likely to be selected.

A lower value makes cost more important than accuracy.

### Predefined Conversion Routes

Predefined routes are handled by `ConversionRoutesConfig`.

If a route from source format to target format is configured there, it takes precedence over dynamic graph routing.

Conceptually:

```text
PDF -> MARK_DOWN = pdfToHtmlAgent, htmlToMarkdownAgent
```

The exact configuration format depends on `ConversionRoutesConfig`.

## Implementation Summary

The core routing algorithm is:

1. If source and target are equal, return an empty path.
2. Check for a predefined route.
3. Build a directed graph from all available conversion agents.
4. Estimate edge weights using cost, quantity, and accuracy.
5. Use Dijkstra's algorithm to find the cheapest path.
6. Execute the selected agents recursively on the artifact tree.
7. Collect the requested output format.

## Important Design Decisions

### Agents Are Edges

Each `DocConversionAgent` is an edge in the graph.

This keeps routing independent from the concrete implementation of individual conversions.

### Formats Are Vertices

Each `DocArtifaktType` is a graph vertex.

This allows the router to reason about possible format transitions without knowing conversion internals.

### Cost Is Dynamic

Costs are estimated for the actual input artifact.

This is important because converting a small document and a large document may have very different costs.

### Quantity Matters

Some conversions multiply the number of artifacts.

The router includes quantity estimation so that routes producing many intermediate artifacts can be rated more realistically.

### Accuracy Is a Routing Criterion

Accuracy does not directly remove agents from the graph.

Instead, inaccurate agents receive a high cost penalty.

This allows fallback routes when no accurate route exists.

### Conversion Supports Trees

The conversion path is linear, but artifacts can branch into children.

This allows Chunk Norris to model document structures such as pages, sections, images, or extracted chunks.

## Example

Given these agents:

| Agent | Source | Target | Accuracy |
|---|---|---|---|
| `pdfToHtmlAgent` | `PDF` | `HTML` | high |
| `htmlToMarkdownAgent` | `HTML` | `MARK_DOWN` | high |
| `pdfToTextAgent` | `PDF` | `TEXT` | low |
| `markdownToTextAgent` | `MARK_DOWN` | `TEXT` | high |

Request:

```java
String text = docConversionRouter.doConversion(
    new File("manual.pdf"),
    DocArtifaktType.PDF,
    DocArtifaktType.TEXT,
    ConversionAccuracy.HIGH
);
```

Possible routes:

```text
PDF -> TEXT
PDF -> HTML -> MARK_DOWN -> TEXT
```

If `pdfToTextAgent` has low accuracy, the router applies the inaccurate surcharge.

The selected route may become:

```text
PDF -> HTML -> MARK_DOWN -> TEXT
```

The router then executes:

```text
pdfToHtmlAgent
htmlToMarkdownAgent
markdownToTextAgent
```

Finally, `DocContentCollector` collects all `TEXT` artifacts and returns the combined text.

## Related Classes

| Class | Responsibility |
|---|---|
| `DocConversionRouter` | Finds and executes conversion routes |
| `DocConversionAgent` | Converts one format into another |
| `DocArtifakt` | Represents a document artifact and its children |
| `DocArtifaktType` | Describes artifact formats |
| `ConversionAccuracy` | Describes desired or provided conversion quality |
| `ConversionRoutesConfig` | Provides predefined conversion routes |
| `DocContentCollector` | Collects converted content from the artifact tree |
| `BeanNameFinder` | Resolves Spring bean names for logging |

_This document was generated with Doc|Pipe and gemini-3-flash-preview_

