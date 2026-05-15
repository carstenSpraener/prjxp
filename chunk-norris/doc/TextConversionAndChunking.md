# Document Conversion Mechanism in Chunk Norris

The `DocConversionRouter` is the central orchestrator responsible for transforming documents from a source format to a target format. Instead of relying on a static mapping, it treats document formats as nodes in a graph and conversion agents as weighted edges, allowing it to find the most efficient conversion path.

## Core Architecture

### 1. Graph-Based Routing
The system models the conversion process as a directed weighted graph:
- **Vertices**: `DocArtifaktType` (e.g., PDF, DOCX, HTML).
- **Edges**: `DocConversionAgent` instances that can transform one specific format into another.

### 2. Pathfinding Logic
To convert a document, the router determines the "best" path using the following priority:

1.  **Predefined Routes**: It first checks `ConversionRoutesConfig`. If a manually configured route exists for the source and target formats, it is used immediately.
2.  **Dynamic Pathfinding (Dijkstra)**: If no predefined route exists, the system builds a graph of all available agents and uses **Dijkstra's Shortest Path Algorithm** to find the optimal sequence of conversions.

### 3. Cost Estimation & Accuracy
The "weight" of an edge in the graph is not static; it is calculated dynamically based on the document and desired accuracy:

- **Base Cost**: Calculated as `agent.estimateCosts(f) * agent.estimateQuantity(f)`.
- **Accuracy Surcharge**: If an agent's accuracy rank is lower than the requested `ConversionAccuracy`, a significant penalty (`inaccurateSurcharge`) is added to the edge weight. This pushes the router to prefer more accurate (though potentially more "expensive") paths.

## Conversion Workflow

The conversion process follows these steps:

1.  **Path Discovery**: `findBestPath()` identifies the sequence of `DocConversionAgent`s.
2.  **Recursive Transformation**: 
    - The `doConversion` method applies the first agent in the path to the `DocArtifakt`.
    - If the conversion results in child artifacts (fragmentation), the process recursively applies the remaining agents in the path to each child.
3.  **Content Collection**: Once the final target format is reached, the `DocContentCollector` aggregates the resulting text or artifacts.

## Key Components

| Component | Responsibility |
| :--- | :--- |
| `DocConversionAgent` | Performs the actual transformation from source to target format. |
| `DocArtifakt` | Represents the document state and its hierarchical structure during conversion. |
| `ConversionRoutesConfig` | Provides overrides for the automated pathfinding. |
| `ConversionAccuracy` | Defines the required quality level, influencing the path selection. |
| `DocContentCollector` | Extracts the final processed content from the resulting artifact tree. |
```