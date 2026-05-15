# Document Conversion Mechanism in Chunk Norris

The `DocConversionRouter` is the central orchestrator for transforming documents from one format to another. Instead of relying on a simple 1:1 mapping, it treats document formats as nodes in a graph and conversion agents as weighted edges, allowing for multi-step conversion paths.

## Core Architecture

### 1. Graph-Based Routing
The system models document formats (`DocArtifaktType`) as vertices and `DocConversionAgent` implementations as directed edges. 

- **Dynamic Pathfinding**: If no direct conversion exists between a source and target format, the router searches for the "best" path through intermediate formats.
- **Dijkstra's Algorithm**: The router uses the `DijkstraShortestPath` algorithm to find the most efficient route based on calculated weights.

### 2. Cost Estimation and Weighting
The "best" path is not necessarily the shortest in terms of steps, but the one with the lowest estimated cost. The weight of an edge is calculated via `estimateConversionCosts`:

$$\text{Total Cost} = (\text{Unit Cost} \times \text{Estimated Quantity}) + \text{Accuracy Surcharge}$$

- **Unit Cost & Quantity**: Provided by the specific `DocConversionAgent` based on the input `DocArtifakt`.
- **Accuracy Surcharge**: If an agent's accuracy rank is lower than the requested `ConversionAccuracy`, a significant penalty (`inaccurateSurcharge`) is added to the weight, steering the router toward more accurate (even if longer) paths.

### 3. Conversion Pipeline
The conversion process follows these steps:
1. **Route Selection**: 
   - Checks `ConversionRoutesConfig` for predefined (hardcoded) routes.
   - If none exist, it builds the graph and calculates the optimal path using Dijkstra.
2. **Recursive Execution**: 
   - The selected list of agents is applied sequentially.
   - The process is recursive: if an agent produces child artifacts, the remaining conversion chain is applied to each child.
3. **Content Collection**: 
   - After the chain is complete, `DocContentCollector` is used to extract the final text or artifacts from the resulting structure.

## Key Components

| Component | Responsibility |
| :--- | :--- |
| `DocConversionAgent` | Defines source/target formats and performs the actual transformation. |
| `DocArtifakt` | Represents the document state and its hierarchical structure during conversion. |
| `ConversionRoutesConfig` | Allows overriding the automatic pathfinding with predefined routes. |
| `ConversionAccuracy` | A ranking system used to penalize low-quality conversion paths. |

## Workflow Summary
`File` $\rightarrow$ `findBestPath()` $\rightarrow$ `List<DocConversionAgent>` $\rightarrow$ `doConversion()` (Recursive) $\rightarrow$ `DocContentCollector` $\rightarrow$ `Final Output`

_This document was generated with Doc|Pipe and gemma4:31B_
