# Document Conversion Mechanism in Chunk Norris

The `DocConversionRouter` is the central orchestrator responsible for transforming documents from a source format to a target format. It treats the available conversion agents as a weighted graph and finds the most efficient path to achieve the desired output.

## Core Architecture

### 1. Graph-Based Routing
The system models document formats as **vertices** and `DocConversionAgent` implementations as **weighted edges**. 

- **Vertices**: `DocArtifaktType` (e.g., PDF, DOCX, TXT).
- **Edges**: `DocConversionAgent` (the logic that converts Format A $\rightarrow$ Format B).

### 2. Pathfinding Logic
To determine the best sequence of conversions, the router follows this priority:

1.  **Predefined Routes**: It first checks `ConversionRoutesConfig`. If a hard-coded optimal path exists for the requested source and target, it is used immediately.
2.  **Dynamic Pathfinding (Dijkstra)**: If no predefined route exists, the system builds a directed graph and uses the **Dijkstra Shortest Path** algorithm to find the "cheapest" route.

### 3. Cost Estimation & Accuracy
The "weight" of an edge is not static; it is calculated dynamically based on the specific file and requested accuracy:

$$\text{Total Cost} = (\text{Unit Cost} \times \text{Estimated Quantity}) + \text{Surcharge}$$

- **Unit Cost & Quantity**: Provided by the agent based on the `DocArtifakt`.
- **Accuracy Surcharge**: If an agent's accuracy rank is lower than the requested `ConversionAccuracy`, a significant penalty (`inaccurateSurcharge`) is added to the cost, steering the router toward more accurate (though potentially more expensive) agents.

## Conversion Process

The conversion is executed in a recursive pipeline:

1.  **Path Selection**: `findBestPath()` identifies the list of agents to be used.
2.  **Recursive Execution**: 
    - The first agent in the path converts the `DocArtifakt`.
    - If the conversion results in child artifacts (splitting a document into chunks), the remaining agents in the path are applied recursively to every child.
3.  **Content Collection**: Once the final agent in the path has processed the artifacts, the `DocContentCollector` aggregates the resulting content into the final output.

## Summary Flow
`File` $\rightarrow$ `DocConversionRouter` $\rightarrow$ `Pathfinding (Predefined or Dijkstra)` $\rightarrow$ `Sequential Agent Execution` $\rightarrow$ `Content Collection` $\rightarrow$ `Final String/Artifact`
```