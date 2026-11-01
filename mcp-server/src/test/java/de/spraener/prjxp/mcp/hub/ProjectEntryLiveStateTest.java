package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.common.config.ProjectDefinition;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The live-update state fields of {@link ProjectEntry} (volatile, explicit accessors).
 */
class ProjectEntryLiveStateTest {

    private ProjectEntry entry() {
        return new ProjectEntry("p", Path.of("/projects/p"), ProjectEntry.Kind.LIVE,
                new ProjectDefinition(), null);
    }

    @Test
    void liveStateDefaultsToZeroAndFalse() {
        ProjectEntry entry = entry();

        assertThat(entry.getNextScanAt()).isZero();
        assertThat(entry.getCurrentIntervalMs()).isZero();
        assertThat(entry.isRescanRequested()).isFalse();
        assertThat(entry.isEmbeddingInFlight()).isFalse();
    }

    @Test
    void liveStateSettersRoundTrip() {
        ProjectEntry entry = entry();

        entry.setNextScanAt(1_234L);
        entry.setCurrentIntervalMs(600_000L);
        entry.setRescanRequested(true);
        entry.setEmbeddingInFlight(true);

        assertThat(entry.getNextScanAt()).isEqualTo(1_234L);
        assertThat(entry.getCurrentIntervalMs()).isEqualTo(600_000L);
        assertThat(entry.isRescanRequested()).isTrue();
        assertThat(entry.isEmbeddingInFlight()).isTrue();
    }
}
