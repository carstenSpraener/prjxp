package de.spraener.prjxp.common.graph;

/** Metadata keys written into {@code PxChunk.metadata} by the code-graph pipeline. */
public final class GraphMetadata {
    public static final String GRAPH_NODE = "graph_node";
    public static final String LEIDEN_CLUSTER_ID = "leiden_cluster_id";
    public static final String LEIDEN_WEIGHT = "leiden_weight";
    private GraphMetadata() {}
}
