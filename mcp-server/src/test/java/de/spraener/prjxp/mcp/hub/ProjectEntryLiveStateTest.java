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
        return new ProjectEntry("p", Path.of("/projects/p"), ProjectEntry.Kind.LIVE, "live",
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

    @Test
    void embeddingRunningAndProgressDefaultToFalseAndZero() {
        ProjectEntry entry = entry();

        assertThat(entry.isEmbeddingRunning()).isFalse();
        assertThat(entry.getLiveUpdateProgress()).isZero();
    }

    @Test
    void embeddingRunningAndProgressRoundTrip() {
        ProjectEntry entry = entry();

        entry.setEmbeddingRunning(true);
        entry.setLiveUpdateProgress(45);

        assertThat(entry.isEmbeddingRunning()).isTrue();
        assertThat(entry.getLiveUpdateProgress()).isEqualTo(45);
    }

    @Test
    void liveUpdateProgressIsClampedTo0to100() {
        ProjectEntry entry = entry();

        entry.setLiveUpdateProgress(-5);
        assertThat(entry.getLiveUpdateProgress()).isZero();
        entry.setLiveUpdateProgress(150);
        assertThat(entry.getLiveUpdateProgress()).isEqualTo(100);
    }
}
