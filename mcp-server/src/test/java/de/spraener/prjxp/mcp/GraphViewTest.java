package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.model.EdgeType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphViewTest {

    @Test
    void unavailableSetsErrorAndEmptyLists() {
        GraphView view = GraphView.unavailable("Graph nicht verfügbar, bitte reindexieren");

        assertThat(view.error()).isEqualTo("Graph nicht verfügbar, bitte reindexieren");
        assertThat(view.project()).isNull();
        assertThat(view.file()).isNull();
        assertThat(view.nodeId()).isNull();
        assertThat(view.clusterId()).isNull();
        assertThat(view.weight()).isNull();
        assertThat(view.outgoing()).isEmpty();
        assertThat(view.incoming()).isEmpty();
        assertThat(view.candidates()).isEmpty();
        // no node resolved -> no per-direction counts
        assertThat(view.totalOutgoing()).isNull();
        assertThat(view.totalIncoming()).isNull();
        // no node resolved -> no module aggregation
        assertThat(view.outgoingByModule()).isNull();
        assertThat(view.incomingByModule()).isNull();
    }

    @Test
    void ambiguousSetsCandidatesAndNullError() {
        GraphView view = GraphView.ambiguous("demo", "A.java", List.of("src/x/A.java", "src/y/A.java"));

        assertThat(view.project()).isEqualTo("demo");
        assertThat(view.file()).isEqualTo("A.java");
        assertThat(view.candidates()).containsExactly("src/x/A.java", "src/y/A.java");
        assertThat(view.error()).isNull();
        assertThat(view.nodeId()).isNull();
        assertThat(view.outgoing()).isEmpty();
        assertThat(view.incoming()).isEmpty();
        // candidates instead of edges -> no per-direction counts
        assertThat(view.totalOutgoing()).isNull();
        assertThat(view.totalIncoming()).isNull();
        // candidates instead of edges -> no module aggregation
        assertThat(view.outgoingByModule()).isNull();
        assertThat(view.incomingByModule()).isNull();
    }

    @Test
    void neighborAccessorsAndRecordEquality() {
        GraphNeighbor a = new GraphNeighbor(EdgeType.IMPORT, "demo.B", "src/B.java", false);
        GraphNeighbor b = new GraphNeighbor(EdgeType.IMPORT, "demo.B", "src/B.java", false);
        GraphNeighbor c = new GraphNeighbor(EdgeType.CALL, "demo.B", null, true);

        assertThat(a.type()).isEqualTo(EdgeType.IMPORT);
        assertThat(a.neighborId()).isEqualTo("demo.B");
        assertThat(a.neighborFile()).isEqualTo("src/B.java");
        assertThat(a.external()).isFalse();
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }
}
