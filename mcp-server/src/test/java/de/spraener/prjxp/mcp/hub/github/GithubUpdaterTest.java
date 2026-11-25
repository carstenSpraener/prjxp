package de.spraener.prjxp.mcp.hub.github;

import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.mcp.hub.HubProjectRegistry;
import de.spraener.prjxp.mcp.hub.PipelineOrchestrator;
import de.spraener.prjxp.mcp.hub.ProjectEntry;
import de.spraener.prjxp.mcp.testsupport.TestProcess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 03: {@link GithubUpdater} — pull + forced re-embed, gated on the {@code gitUrl} origin
 * marker. Uses a REAL {@link GithubGitClient} pointed at a fake {@code git} JVM process and
 * Mockito mocks for the registry + orchestrator.
 */
class GithubUpdaterTest {

    @TempDir
    Path tempDir;

    private HubProjectRegistry registry;
    private PipelineOrchestrator orchestrator;
    private Path rootDir;
    private GithubOperationTracker tracker;   // real — the async feedback record
    private TokenCipher tokenCipher;          // real, over a MockEnvironment with a known key

    @BeforeEach
    void setUp() throws Exception {
        registry = mock(HubProjectRegistry.class);
        orchestrator = mock(PipelineOrchestrator.class);
        rootDir = Files.createDirectories(tempDir.resolve("clone"));
        tracker = new GithubOperationTracker();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PRJXP_HUB_GITHUB_TOKEN_KEY", "test-key");
        tokenCipher = new TokenCipher(new HubProperties(), env);
    }

    private ProjectEntry entry(String name, String gitUrl) {
        return entry(name, gitUrl, null);
    }

    private ProjectEntry entry(String name, String gitUrl, String gitToken) {
        ProjectDefinition def = new ProjectDefinition();
        def.setName(name);
        def.setGitUrl(gitUrl);
        def.setGitToken(gitToken);
        return new ProjectEntry(name, rootDir, ProjectEntry.Kind.SNAPSHOT, "github", def,
                new PrjXPEmbeddingStoreReference());
    }

    /**
     * A fake {@code git} executable: for {@code pull} it writes a {@code .git-pull-ran} marker
     * next to the {@code -C} dir. When {@code fail} is set it exits non-zero (after writing the
     * marker, so the async test can observe the task ran).
     */
    private List<String> writeFakeGit(String fileName, boolean fail) throws Exception {
        return TestProcess.git(tempDir.resolve(fileName), fail ? 1 : 0, null);
    }

    private GithubUpdater updaterWith(List<String> gitScript) {
        return new GithubUpdater(registry, new GithubGitClient(new HubProperties(), gitScript),
                orchestrator, tracker, tokenCipher);
    }

    /**
     * Like {@link #writeFakeGit} but additionally appends every invocation's arguments to
     * {@code logFile} — lets the tests assert on the {@code http.extraheader} token flow.
     */
    private List<String> writeFakeGitWithArgLog(String fileName, boolean fail, Path logFile) throws Exception {
        return TestProcess.git(tempDir.resolve(fileName), fail ? 1 : 0, logFile);
    }

    @Test
    void updatePullsAndEnqueuesForGithubProject() throws Exception {
        when(registry.entry("gh")).thenReturn(Optional.of(entry("gh", "https://github.com/foo/bar")));
        GithubUpdater updater = updaterWith(writeFakeGit("git-ok", false));

        assertThat(updater.update("gh")).isTrue();

        assertThat(rootDir.resolve(".git-pull-ran")).exists();   // the fake git ran the pull
        verify(orchestrator).enqueueForced("gh");
    }

    @Test
    void updateIsNoOpWithoutGitUrlMarker() throws Exception {
        when(registry.entry("snap")).thenReturn(Optional.of(entry("snap", null)));   // a snapshot/library
        GithubUpdater updater = updaterWith(writeFakeGit("git-ok", false));

        assertThat(updater.update("snap")).isFalse();

        assertThat(rootDir.resolve(".git-pull-ran")).doesNotExist();
        verify(orchestrator, never()).enqueueForced("snap");
    }

    @Test
    void updateForUnknownProjectReturnsFalseWithoutException() throws Exception {
        when(registry.entry("ghost")).thenReturn(Optional.empty());
        GithubUpdater updater = updaterWith(writeFakeGit("git-ok", false));

        assertThat(updater.update("ghost")).isFalse();

        assertThat(rootDir.resolve(".git-pull-ran")).doesNotExist();
        verify(orchestrator, never()).enqueueForced("ghost");
    }

    @Test
    void updateAsyncSwallowsPullFailure() throws Exception {
        when(registry.entry("gh")).thenReturn(Optional.of(entry("gh", "https://github.com/foo/bar")));
        GithubUpdater updater = updaterWith(writeFakeGit("git-fail", true));

        assertThatCode(() -> updater.updateAsync("gh")).doesNotThrowAnyException();

        // the task ran to completion on the dedicated executor (the failing fake git left its marker)
        Path marker = rootDir.resolve(".git-pull-ran");
        awaitFile(marker, 10_000);
        assertThat(marker).exists();
        verify(orchestrator, never()).enqueueForced("gh");
    }

    // ------------------------------------------------------------------ Phase 05: tracker feedback

    @Test
    void updateAsyncRecordsSuccessInTracker() throws Exception {
        when(registry.entry("gh")).thenReturn(Optional.of(entry("gh", "https://github.com/foo/bar")));
        GithubUpdater updater = updaterWith(writeFakeGit("git-ok", false));

        updater.updateAsync("gh");

        awaitTrackerState(tracker, "gh", GithubOperation.SUCCESS);
        assertThat(tracker.latest("gh").orElseThrow().message()).isEqualTo("Updated gh");
    }

    @Test
    void updateAsyncRecordsFailureInTrackerWithPullError() throws Exception {
        when(registry.entry("gh")).thenReturn(Optional.of(entry("gh", "https://github.com/foo/bar")));
        GithubUpdater updater = updaterWith(writeFakeGit("git-fail", true));

        updater.updateAsync("gh");

        awaitTrackerState(tracker, "gh", GithubOperation.FAILED);
        assertThat(tracker.latest("gh").orElseThrow().message()).contains("git pull exited with code 1");
    }

    @Test
    void updateAsyncRecordsNoOpSkipAsSuccess() throws Exception {
        when(registry.entry("snap")).thenReturn(Optional.of(entry("snap", null)));   // a snapshot/library
        GithubUpdater updater = updaterWith(writeFakeGit("git-ok", false));

        updater.updateAsync("snap");

        awaitTrackerState(tracker, "snap", GithubOperation.SUCCESS);
        assertThat(tracker.latest("snap").orElseThrow().message()).isEqualTo("Skipped — not a GitHub project");
    }

    // ------------------------------------------------------------------ Phase 06: PAT token flow

    @Test
    void updateWithEncryptedTokenDecryptsAndPassesExtraheader() throws Exception {
        String encrypted = tokenCipher.encrypt("ghp_secret123");
        when(registry.entry("gh")).thenReturn(Optional.of(entry("gh", "https://github.com/foo/bar", encrypted)));
        Path logFile = tempDir.resolve("git-args.log");
        GithubUpdater updater = updaterWith(writeFakeGitWithArgLog("git-ok", false, logFile));

        assertThat(updater.update("gh")).isTrue();

        assertThat(rootDir.resolve(".git-pull-ran")).exists();
        assertThat(Files.readString(logFile)).contains("http.extraheader=Authorization: Bearer ghp_secret123");
        verify(orchestrator).enqueueForced("gh");
    }

    @Test
    void updateWithEncryptedTokenButNoKeyThrowsTokenCipherException() throws Exception {
        String encrypted = tokenCipher.encrypt("ghp_secret123");   // encrypted with "test-key"
        when(registry.entry("gh")).thenReturn(Optional.of(entry("gh", "https://github.com/foo/bar", encrypted)));
        TokenCipher noKey = new TokenCipher(new HubProperties(), new MockEnvironment());   // no key property set
        GithubUpdater updater = new GithubUpdater(registry,
                new GithubGitClient(new HubProperties(), writeFakeGit("git-ok", false)),
                orchestrator, tracker, noKey);

        assertThatThrownBy(() -> updater.update("gh"))
                .isInstanceOf(TokenCipherException.class)
                .hasMessageContaining("PRJXP_HUB_GITHUB_TOKEN_KEY");

        assertThat(rootDir.resolve(".git-pull-ran")).doesNotExist();   // no pull attempted
    }

    @Test
    void updateAsyncWithEncryptedTokenButNoKeyRecordsFailedTrackerOp() throws Exception {
        String encrypted = tokenCipher.encrypt("ghp_secret123");
        when(registry.entry("gh")).thenReturn(Optional.of(entry("gh", "https://github.com/foo/bar", encrypted)));
        TokenCipher noKey = new TokenCipher(new HubProperties(), new MockEnvironment());   // no key property set
        GithubUpdater updater = new GithubUpdater(registry,
                new GithubGitClient(new HubProperties(), writeFakeGit("git-ok", false)),
                orchestrator, tracker, noKey);

        updater.updateAsync("gh");

        awaitTrackerState(tracker, "gh", GithubOperation.FAILED);
        assertThat(tracker.latest("gh").orElseThrow().message())
                .contains("PRJXP_HUB_GITHUB_TOKEN_KEY");
    }

    @Test
    void updateWithoutTokenHasNoExtraheader() throws Exception {
        when(registry.entry("gh")).thenReturn(Optional.of(entry("gh", "https://github.com/foo/bar", null)));   // public repo
        Path logFile = tempDir.resolve("git-args.log");
        GithubUpdater updater = updaterWith(writeFakeGitWithArgLog("git-ok", false, logFile));

        assertThat(updater.update("gh")).isTrue();

        assertThat(Files.readString(logFile)).doesNotContain("http.extraheader");
    }

    /** Polls for {@code file} until it exists or the timeout elapses (no awaitility in the test bundle). */
    private static void awaitFile(Path file, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!Files.exists(file)) {
            if (System.currentTimeMillis() > deadline) {
                return;   // the assertion after the call fails
            }
            Thread.sleep(50);
        }
    }

    /** Polls the tracker until the key reaches {@code status} or the timeout elapses. */
    private static void awaitTrackerState(GithubOperationTracker tracker, String key, String status)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (tracker.latest(key).map(GithubOperation::status).orElse(null) == null
                || !status.equals(tracker.latest(key).orElseThrow().status())) {
            if (System.currentTimeMillis() > deadline) {
                return;   // the assertion after the call fails
            }
            Thread.sleep(50);
        }
    }
}
