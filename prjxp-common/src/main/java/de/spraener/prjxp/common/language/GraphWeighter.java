package de.spraener.prjxp.common.language;

import de.spraener.prjxp.common.model.CodeGraph;

/**
 * Rates the nodes of a code graph: assigns each node a community (cluster) id and an
 * importance weight in {@code [0,1]}.
 *
 * <p>Pluggable by design. The v1 default is {@link CentralityWeighter} (degree
 * centrality + connected components). A Leiden-based implementation (internal/total
 * degree + Leiden communities) can be swapped in later without touching the rest of the
 * pipeline — the weighter is the single seam between the graph and the
 * {@code leiden_cluster_id}/{@code leiden_weight} chunk metadata.
 */
public interface GraphWeighter {

    /**
     * Returns a new {@link CodeGraph} whose nodes carry a {@code clusterId} and a
     * {@code weight}. The input graph is not mutated.
     */
    CodeGraph apply(CodeGraph graph);
}
