package de.spraener.prjxp.tibed;

import de.spraener.prjxp.common.graph.SignatureMetadata;
import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AST-neighbour sidecar is code-graph data available before embedding; the merger stamps it
 * into chunk metadata so every embedding writer (CLI, hub export, live update) produces the same field.
 */
class AstNeighboursMergerTest {

    private static PxChunk chunk(String id) {
        return PxChunk.create(c -> {
            c.setId(id);
            c.setContent("content of " + id);
        });
    }

    @Test
    void merge_stampsCommaSeparatedNeighbours() {
        PxChunk chunk = chunk("A#one");

        AstNeighboursMerger.merge(chunk, Map.of("A#one", List.of("B#two", "C#three")));

        assertThat(chunk.getMetadata())
                .containsEntry(SignatureMetadata.AST_NEIGHBOURS,
                        "B#two" + SignatureMetadata.LIST_SEPARATOR + "C#three");
    }

    @Test
    void merge_withoutSidecarEntry_leavesMetadataUntouched() {
        PxChunk chunk = chunk("A#one");

        AstNeighboursMerger.merge(chunk, Map.of("other", List.of("B#two")));

        assertThat(chunk.getMetadata()).doesNotContainKey(SignatureMetadata.AST_NEIGHBOURS);
    }

    @Test
    void merge_withEmptyNeighbourList_leavesMetadataUntouched() {
        PxChunk chunk = chunk("A#one");

        AstNeighboursMerger.merge(chunk, Map.of("A#one", List.of()));

        assertThat(chunk.getMetadata()).doesNotContainKey(SignatureMetadata.AST_NEIGHBOURS);
    }

    @Test
    void merge_isNoOpForNullChunkOrEmptySidecar() {
        AstNeighboursMerger.merge(null, Map.of("A#one", List.of("B#two")));
        PxChunk chunk = chunk("A#one");
        AstNeighboursMerger.merge(chunk, null);
        AstNeighboursMerger.merge(chunk, Map.of());

        assertThat(chunk.getMetadata()).doesNotContainKey(SignatureMetadata.AST_NEIGHBOURS);
    }

    @Test
    void mergeAll_stampsEveryMatchingChunkOfABatch() {
        PxChunk first = chunk("A#one");
        PxChunk second = chunk("B#two");
        PxChunk unmatched = chunk("C#three");

        AstNeighboursMerger.mergeAll(List.of(first, second, unmatched),
                Map.of("A#one", List.of("B#two"), "B#two", List.of("A#one")));

        assertThat(first.getMetadata()).containsEntry(SignatureMetadata.AST_NEIGHBOURS, "B#two");
        assertThat(second.getMetadata()).containsEntry(SignatureMetadata.AST_NEIGHBOURS, "A#one");
        assertThat(unmatched.getMetadata()).doesNotContainKey(SignatureMetadata.AST_NEIGHBOURS);
    }

    @Test
    void mergeAll_isNoOpForNullBatchOrEmptySidecar() {
        PxChunk chunk = chunk("A#one");

        AstNeighboursMerger.mergeAll(null, Map.of("A#one", List.of("B#two")));
        AstNeighboursMerger.mergeAll(List.of(chunk), null);
        AstNeighboursMerger.mergeAll(List.of(chunk), Map.of());

        assertThat(chunk.getMetadata()).doesNotContainKey(SignatureMetadata.AST_NEIGHBOURS);
    }
}
