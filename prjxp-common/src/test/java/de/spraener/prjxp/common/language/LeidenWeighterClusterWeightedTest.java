package de.spraener.prjxp.common.language;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.graph.GraphMetadata;
import de.spraener.prjxp.common.model.SemanticGraphSidecar;
import de.spraener.prjxp.common.model.WeightedEdge;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LeidenWeighterClusterWeightedTest {

    private final LeidenWeighter weighter = new LeidenWeighter();

    @Test
    void clustersConnectedPairAtLowGamma() {
        Map<String, String> clusters = weighter.clusterWeighted(
                List.of("a", "b"), List.of(new WeightedEdge("a", "b", 1.0)), 0.1);

        assertThat(clusters).hasSize(2);
        assertThat(clusters.get("a")).isEqualTo(clusters.get("b")).isEqualTo("s0");
    }

    @Test
    void givesDisconnectedNodesSeparateSingletonClusters() {
        Map<String, String> clusters = weighter.clusterWeighted(List.of("a", "b"), List.of(), 0.1);

        assertThat(clusters).hasSize(2);
        assertThat(clusters.get("a")).isNotEqualTo(clusters.get("b"));
        assertThat(clusters.values()).containsExactlyInAnyOrder("s0", "s1");
    }

    @Test
    void findsTwoDisconnectedCommunities() {
        List<String> nodes = List.of("a", "b", "c", "d", "e");
        List<WeightedEdge> edges = List.of(
                new WeightedEdge("a", "b", 1.0),
                new WeightedEdge("b", "c", 1.0),
                new WeightedEdge("a", "c", 1.0),
                new WeightedEdge("d", "e", 1.0));

        Map<String, String> clusters = weighter.clusterWeighted(nodes, edges, 0.1);

        assertThat(clusters.get("a")).isEqualTo(clusters.get("b")).isEqualTo(clusters.get("c"));
        assertThat(clusters.get("d")).isEqualTo(clusters.get("e"));
        assertThat(clusters.get("a")).isNotEqualTo(clusters.get("d"));
    }

    @Test
    void handlesEmptyAndSingleNodeInputs() {
        assertThat(weighter.clusterWeighted(List.of(), List.of(), 0.1)).isEmpty();
        assertThat(weighter.clusterWeighted(List.of("only"), List.of(), 0.1))
                .containsEntry("only", "s0");
    }

    @Test
    void aggregatesReverseEdgesAndIgnoresExternalAndSelfEdges() {
        Map<String, String> clusters = weighter.clusterWeighted(
                List.of("a", "b", "isolated"),
                List.of(
                        new WeightedEdge("a", "b", 0.2),
                        new WeightedEdge("b", "a", 0.2),
                        new WeightedEdge("isolated", "isolated", 3.0),
                        new WeightedEdge("a", "outside", 3.0)),
                0.1);

        assertThat(clusters.get("a")).isEqualTo(clusters.get("b"));
        assertThat(clusters.get("isolated")).isNotEqualTo(clusters.get("a"));
        assertThat(clusters).hasSize(3);
    }

    @Test
    void weightedClusteringIsDeterministic() {
        List<String> nodes = List.of("a", "b", "c", "d");
        List<WeightedEdge> edges = List.of(
                new WeightedEdge("a", "b", 0.9),
                new WeightedEdge("b", "c", 0.8),
                new WeightedEdge("c", "d", 0.9));

        assertThat(weighter.clusterWeighted(nodes, edges, 0.1))
                .isEqualTo(weighter.clusterWeighted(nodes, edges, 0.1));
    }

    @Test
    void exposesSemanticDefaultsAndMetadataKeys() {
        PrjXPConfig.SemanticGraphConfig config = new PrjXPConfig().getSemanticGraph();

        assertThat(config).isNotNull();
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.isCalibrate()).isFalse();
        assertThat(config.getGamma()).isEqualTo(1.0);
        assertThat(config.getThreshold()).isEqualTo(0.85);
        assertThat(config.getK()).isEqualTo(20);
        assertThat(config.isRequireMutual()).isTrue();
        assertThat(GraphMetadata.SEMANTIC_NODE_ID).isEqualTo("semantic_node_id");
        assertThat(GraphMetadata.SEMANTIC_CLUSTER_ID).isEqualTo("semantic_cluster_id");

        SemanticGraphSidecar sidecar = new SemanticGraphSidecar("model", 0.5, 0.85, 20, true, 5, "now");
        assertThat(sidecar.model()).isEqualTo("model");
        assertThat(sidecar.gamma()).isEqualTo(0.5);
        assertThat(sidecar.chunkCount()).isEqualTo(5);
    }
}
