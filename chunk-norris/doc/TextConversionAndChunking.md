# Document Conversion Mechanism in Chunk Norris

The `DocConversionRouter` is the central orchestration engine responsible for transforming documents from a source format to a target format. It treats the conversion process as a graph problem, finding the most efficient path through a series of available conversion agents.

## Core Architecture

### 1. Graph-Based Routing
The system models document formats as **vertices** and `DocConversionAgent` implementations as **weighted edges** in a directed graph.

- **Vertices**: `DocArtifaktType` (e.g., PDF, DOCX, TXT).
- **Edges**: `DocConversionAgent` (the logic that converts Format A $\rightarrow$ Format B).

### 2. Pathfinding Logic
To convert a document, the router determines the "best" path using the following priority:

1.  **Predefined Routes**: The `ConversionRoutesConfig` is checked first. If a hard-coded route exists for the specific start and end formats, it is used immediately.
2.  **Dynamic Pathfinding (Dijkstra)**: If no predefined route exists, the system builds a graph of all available agents and uses **Dijkstra's Shortest Path Algorithm** to find the optimal sequence of conversions.

### 3. Cost Estimation & Weighting
The "weight" of an edge (agent) is not static; it is calculated dynamically based on the specific file and desired accuracy:

$$\text{Total Cost} = (\text{Unit Cost} \times \text{Estimated Quantity}) + \text{Surcharge}$$

- **Unit Cost & Quantity**: Provided by the agent's `estimateCosts()` and `estimateQuantity()` methods.
- **Accuracy Surcharge**: If an agent's inherent accuracy rank is lower than the requested `ConversionAccuracy`, a significant penalty (`inaccurateSurcharge`) is added to the cost, discouraging the router from picking low-quality conversion paths.

## Conversion Workflow

The conversion process follows these steps:

1.  **Path Discovery**: `findBestPath()` identifies the sequence of agents required.
2.  **Recursive Execution**: The `doConversion` method processes the document. If an agent splits a document into multiple children (sub-artifacts), the remaining conversion chain is applied recursively to every child.
3.  **Content Collection**: Once the final target format is reached, the `DocContentCollector` extracts the resulting text or artifacts.

## Key Components

| Component | Responsibility |
| :--- | :--- |
| `DocConversionAgent` | Defines source/target formats and performs the actual transformation. |
| `DocArtifakt` | Represents the document state and its hierarchical structure during conversion. |
| `ConversionRoutesConfig` | Provides manual overrides for specific format pairs. |
| `DocContentCollector` | Aggregates the final output from the processed artifact tree. |

## Summary Flow
`File` $\rightarrow$ `Route Discovery (Predefined $\rightarrow$ Dijkstra)` $\rightarrow$ `Recursive Agent Execution` $\rightarrow$ `Content Collection` $\rightarrow$ `Final String/Artifact`


_This document was generated with DocPipe and gemma4:31B_
