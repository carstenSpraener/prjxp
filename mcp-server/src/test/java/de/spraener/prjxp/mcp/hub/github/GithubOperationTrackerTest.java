package de.spraener.prjxp.mcp.hub.github;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 05: {@link GithubOperationTracker} — the in-memory, capped, transient record of recent
 * async GitHub operations that the WebUI polls for visual feedback.
 */
class GithubOperationTrackerTest {

    private final GithubOperationTracker tracker = new GithubOperationTracker();

    @Test
    void startMarksKeyAsPending() {
        tracker.start("https://github.com/foo/bar");

        var op = tracker.latest("https://github.com/foo/bar").orElseThrow();
        assertThat(op.key()).isEqualTo("https://github.com/foo/bar");
        assertThat(op.status()).isEqualTo(GithubOperation.PENDING);
        assertThat(op.message()).isNull();
        assertThat(op.timestamp()).isPositive();
    }

    @Test
    void completeWithSuccessRecordsSuccessAndMessage() {
        tracker.start("gh");
        tracker.complete("gh", true, "Imported bar");

        var op = tracker.latest("gh").orElseThrow();
        assertThat(op.status()).isEqualTo(GithubOperation.SUCCESS);
        assertThat(op.message()).isEqualTo("Imported bar");
    }

    @Test
    void completeWithFailureRecordsFailedAndMessage() {
        tracker.start("gh");
        tracker.complete("gh", false, "git clone exited with code 128");

        var op = tracker.latest("gh").orElseThrow();
        assertThat(op.status()).isEqualTo(GithubOperation.FAILED);
        assertThat(op.message()).isEqualTo("git clone exited with code 128");
    }

    @Test
    void recentReturnsNewestFirst() throws Exception {
        tracker.start("a");
        Thread.sleep(5);
        tracker.start("b");
        Thread.sleep(5);
        tracker.start("c");

        List<GithubOperation> recent = tracker.recent();
        assertThat(recent).extracting(GithubOperation::key).containsExactly("c", "b", "a");
    }

    @Test
    void recentIsCappedAt50() throws Exception {
        for (int i = 0; i < 60; i++) {
            tracker.start("key-" + i);
            Thread.sleep(2);   // strictly increasing timestamps
        }

        assertThat(tracker.recent()).hasSize(50);
        assertThat(tracker.recent().get(0).key()).isEqualTo("key-59");   // the newest first
    }

    @Test
    void capEvictsOldestBeyond100() throws Exception {
        for (int i = 0; i < 105; i++) {
            tracker.start("key-" + i);
            Thread.sleep(2);   // strictly increasing timestamps
        }

        assertThat(mapSize(tracker)).isLessThanOrEqualTo(100);
        assertThat(tracker.latest("key-0")).isEmpty();          // the oldest were evicted
        assertThat(tracker.latest("key-104")).isPresent();      // the newest survive
    }

    /** The tracker's map is private — read it reflectively for the cap assertion. */
    private static int mapSize(GithubOperationTracker tracker) throws Exception {
        Field field = GithubOperationTracker.class.getDeclaredField("operations");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(tracker)).size();
    }
}
