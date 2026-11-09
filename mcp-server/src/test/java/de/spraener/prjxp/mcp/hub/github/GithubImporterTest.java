package de.spraener.prjxp.mcp.hub.github;

import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.mcp.hub.HubProjectRegistry;
import de.spraener.prjxp.mcp.hub.PipelineOrchestrator;
import de.spraener.prjxp.mcp.hub.ProjectConfigFileParser;
import de.spraener.prjxp.mcp.hub.ProjectEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 03: {@link GithubImporter} — clone → generate prjxp.yaml → register (source "github") →
 * enqueue. Uses a REAL {@link GithubGitClient} pointed at a fake {@code git} shell script, a REAL
 * {@link ProjectConfigFileParser}, and Mockito mocks for the registry + orchestrator.
 */
class GithubImporterTest {

    @TempDir
    Path tempDir;

    private HubProperties props;
    private HubProjectRegistry registry;
    private PipelineOrchestrator orchestrator;
    private ProjectConfigFileParser configParser;
    private GithubOperationTracker tracker;   // real — the async feedback record
    private TokenCipher tokenCipher;          // real, over a MockEnvironment with a known key

    @BeforeEach
    void setUp() throws Exception {
        props = new HubProperties();
        props.setGithubRoot(tempDir.resolve("github").toString());
        props.setGithubJsonlDir(tempDir.resolve("github-chunks").toString());
        Files.createDirectories(Path.of(props.getGithubRoot()));
        // NOTE: the githubJsonlDir is deliberately NOT pre-created here — the importer must create it (Phase 08).

        registry = mock(HubProjectRegistry.class);
        orchestrator = mock(PipelineOrchestrator.class);
        configParser = new ProjectConfigFileParser();
        tracker = new GithubOperationTracker();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("PRJXP_HUB_GITHUB_TOKEN_KEY", "test-key");
        tokenCipher = new TokenCipher(props, env);
    }

    /**
     * A fake {@code git} executable: for {@code clone} it creates the target dir (last argument)
     * with a {@code .git} dir + a {@code .git-clone-ran} marker; for {@code pull} it writes a
     * {@code .git-pull-ran} marker next to the {@code -C} dir. When {@code fail} is set it exits
     * non-zero (after writing the marker, so async tests can observe the task ran).
     */
    private Path writeFakeGit(String fileName, boolean fail) throws Exception {
        Path script = tempDir.resolve(fileName);
        Files.writeString(script, """
                #!/bin/sh
                for a in "$@"; do
                  if [ "$a" = "clone" ]; then
                    target=""
                    for b in "$@"; do target="$b"; done
                    mkdir -p "$target/.git"
                    touch "$target/.git-clone-ran"
                    exit %d
                  fi
                  if [ "$a" = "pull" ]; then
                    dir=""
                    prev=""
                    for b in "$@"; do
                      if [ "$prev" = "-C" ]; then dir="$b"; fi
                      prev="$b"
                    done
                    touch "$dir/.git-pull-ran"
                    exit %d
                  fi
                done
                exit 1
                """.formatted(fail ? 1 : 0, fail ? 1 : 0));
        script.toFile().setExecutable(true);
        return script;
    }

    private GithubImporter importerWith(Path gitScript) {
        return new GithubImporter(props, new GithubGitClient(props, gitScript),
                configParser, registry, orchestrator, tracker, tokenCipher);
    }

    /**
     * Like {@link #writeFakeGit} but additionally appends every invocation's arguments to
     * {@code logFile} — lets the tests assert on the {@code http.extraheader} token flow.
     */
    private Path writeFakeGitWithArgLog(String fileName, boolean fail, Path logFile) throws Exception {
        Path script = tempDir.resolve(fileName);
        Files.writeString(script, """
                #!/bin/sh
                echo "$@" >> "%s"
                for a in "$@"; do
                  if [ "$a" = "clone" ]; then
                    target=""
                    for b in "$@"; do target="$b"; done
                    mkdir -p "$target/.git"
                    touch "$target/.git-clone-ran"
                    exit %d
                  fi
                done
                exit 1
                """.formatted(logFile.toString(), fail ? 1 : 0));
        script.toFile().setExecutable(true);
        return script;
    }

    // ------------------------------------------------------------------ deriveName

    @Test
    void deriveNameFromVariousUrls() {
        GithubImporter importer = importerWith(Path.of("git"));   // deriveName never shells out

        assertThat(importer.deriveName("https://github.com/foo/bar")).isEqualTo("bar");
        assertThat(importer.deriveName("https://github.com/foo/bar.git")).isEqualTo("bar");
        assertThat(importer.deriveName("git@github.com:foo/bar.git")).isEqualTo("bar");
        assertThat(importer.deriveName("bare")).isEqualTo("bare");   // no slash -> the whole string
        assertThat(importer.deriveName("")).isEqualTo("github");     // empty -> fallback name
    }

    // ------------------------------------------------------------------ importFromUrl

    @Test
    void importFromUrlClonesGeneratesYamlRegistersAndEnqueues() throws Exception {
        when(registry.entry("bar")).thenReturn(Optional.empty());
        GithubImporter importer = importerWith(writeFakeGit("git-ok", false));

        importer.importFromUrl("https://github.com/foo/bar");

        Path dir = Path.of(props.getGithubRoot(), "bar");
        assertThat(dir.resolve(".git-clone-ran")).exists();   // the fake git ran the clone
        assertThat(dir.resolve(".git")).isDirectory();

        // the generated prjxp.yaml (parsed back with the real parser)
        ProjectDefinition def = configParser.parse(dir, "bar");
        assertThat(def.getName()).isEqualTo("bar");
        assertThat(def.getRootDir()).isEqualTo(".");
        assertThat(def.getJsonlFile()).isEqualTo(Path.of(props.getGithubJsonlDir(), "bar.jsonl").toString());   // absolute: hub-managed
        assertThat(def.getGitUrl()).isEqualTo("https://github.com/foo/bar");   // origin marker

        verify(registry).registerProject("bar", dir, ProjectEntry.Kind.SNAPSHOT, "github");
        verify(orchestrator).enqueueForced("bar");
    }

    @Test
    void importFromUrlCreatesGithubJsonlDir() throws Exception {
        when(registry.entry("bar")).thenReturn(Optional.empty());
        GithubImporter importer = importerWith(writeFakeGit("git-ok", false));

        importer.importFromUrl("https://github.com/foo/bar");

        // the setup does NOT pre-create the jsonl dir — the importer must create it (Phase 08)
        assertThat(Files.isDirectory(Path.of(props.getGithubJsonlDir()))).isTrue();
    }

    @Test
    void importFromUrlThrowsWhenAlreadyRegisteredWithoutPopulatedClone() throws Exception {
        // Phase 09: an entry exists but the clone dir is NOT populated — the old contract re-ran the
        // old project via reloadDefinition; the new contract rejects the import (the clone dir would
        // be orphaned). The entry's rootDir here is the github clone dir itself — the collision check
        // fires regardless of the entry's rootDir.
        when(registry.entry("bar")).thenReturn(Optional.of(
                new ProjectEntry("bar", Path.of(props.getGithubRoot(), "bar"),
                        ProjectEntry.Kind.SNAPSHOT, "github", new ProjectDefinition(), null)));
        GithubImporter importer = importerWith(writeFakeGit("git-fail", true));   // a clone attempt would fail

        assertThatThrownBy(() -> importer.importFromUrl("https://github.com/foo/bar"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bar")
                .hasMessageContaining("delete");

        assertThat(Path.of(props.getGithubRoot(), "bar", ".git-clone-ran")).doesNotExist();   // no clone attempted
        verify(orchestrator, never()).enqueueForced("bar");
    }

    @Test
    void importFromUrlSkipsPopulatedClone() throws Exception {
        Path dir = Path.of(props.getGithubRoot(), "bar");
        Files.createDirectories(dir.resolve(".git"));   // pre-existing clone
        GithubImporter importer = importerWith(writeFakeGit("git-fail", true));   // a clone attempt would fail

        importer.importFromUrl("https://github.com/foo/bar");   // must not throw — no clone attempted

        assertThat(dir.resolve(".git-clone-ran")).doesNotExist();
        verifyNoInteractions(registry);
        verifyNoInteractions(orchestrator);
    }

    @Test
    void cloneFailurePropagatesFromBlockingImport() throws Exception {
        GithubImporter importer = importerWith(writeFakeGit("git-fail", true));

        assertThatThrownBy(() -> importer.importFromUrl("https://github.com/foo/bar"))
                .isInstanceOf(GithubGitException.class);

        verify(orchestrator, never()).enqueueForced("bar");
    }

    @Test
    void importFromUrlAsyncSwallowsCloneFailure() throws Exception {
        GithubImporter importer = importerWith(writeFakeGit("git-fail", true));

        assertThatCode(() -> importer.importFromUrlAsync("https://github.com/foo/bar"))
                .doesNotThrowAnyException();

        // the task ran to completion on the dedicated executor (the failing fake git left its marker)
        Path marker = Path.of(props.getGithubRoot(), "bar", ".git-clone-ran");
        awaitFile(marker, 10_000);
        assertThat(marker).exists();
        verify(orchestrator, never()).enqueueForced("bar");
    }

    // ------------------------------------------------------------------ Phase 05: tracker feedback

    @Test
    void importFromUrlAsyncRecordsSuccessInTracker() throws Exception {
        when(registry.entry("bar")).thenReturn(Optional.empty());
        GithubImporter importer = importerWith(writeFakeGit("git-ok", false));

        importer.importFromUrlAsync("https://github.com/foo/bar");

        awaitTrackerState(tracker, "https://github.com/foo/bar", GithubOperation.SUCCESS);
        assertThat(tracker.latest("https://github.com/foo/bar").orElseThrow().message())
                .isEqualTo("Imported bar");
    }

    @Test
    void importFromUrlAsyncRecordsFailureInTrackerWithGitMessage() throws Exception {
        GithubImporter importer = importerWith(writeFakeGit("git-fail", true));

        importer.importFromUrlAsync("https://github.com/foo/bar");

        awaitTrackerState(tracker, "https://github.com/foo/bar", GithubOperation.FAILED);
        assertThat(tracker.latest("https://github.com/foo/bar").orElseThrow().message())
                .contains("git clone exited with code 1");
    }

    @Test
    void importFromUrlAsyncRecordsIdempotentSkipAsSuccess() throws Exception {
        Path dir = Path.of(props.getGithubRoot(), "bar");
        Files.createDirectories(dir.resolve(".git"));   // pre-existing clone
        GithubImporter importer = importerWith(writeFakeGit("git-fail", true));   // a clone attempt would fail

        importer.importFromUrlAsync("https://github.com/foo/bar");

        awaitTrackerState(tracker, "https://github.com/foo/bar", GithubOperation.SUCCESS);
        assertThat(tracker.latest("https://github.com/foo/bar").orElseThrow().message())
                .isEqualTo("Already cloned — skipped");
    }

    // ------------------------------------------------------------------ Phase 06: PAT token flow

    @Test
    void importFromUrlWithTokenEncryptsTokenIntoYamlAndPassesExtraheader() throws Exception {
        when(registry.entry("bar")).thenReturn(Optional.empty());
        Path logFile = tempDir.resolve("git-args.log");
        GithubImporter importer = importerWith(writeFakeGitWithArgLog("git-ok", false, logFile));

        importer.importFromUrl("https://github.com/foo/bar", "ghp_secret123");

        String yaml = Files.readString(Path.of(props.getGithubRoot(), "bar", "prjxp.yaml"));
        assertThat(yaml).contains("gitUrl:");
        assertThat(yaml).contains("gitToken:");
        assertThat(yaml).contains("v1:");
        assertThat(yaml).doesNotContain("ghp_secret123");   // the plaintext token is NEVER persisted
        assertThat(Files.readString(logFile)).contains("http.extraheader=Authorization: Bearer ghp_secret123");
    }

    @Test
    void importFromUrlWithoutTokenHasNoGitTokenLineAndNoExtraheader() throws Exception {
        when(registry.entry("bar")).thenReturn(Optional.empty());
        Path logFile = tempDir.resolve("git-args.log");
        GithubImporter importer = importerWith(writeFakeGitWithArgLog("git-ok", false, logFile));

        importer.importFromUrl("https://github.com/foo/bar");   // the 1-arg delegate (public repo)

        String yaml = Files.readString(Path.of(props.getGithubRoot(), "bar", "prjxp.yaml"));
        assertThat(yaml).contains("gitUrl:");
        assertThat(yaml).doesNotContain("gitToken");
        assertThat(Files.readString(logFile)).doesNotContain("http.extraheader");
    }

    @Test
    void importFromUrlWithTokenButNoKeyThrowsTokenCipherException() throws Exception {
        TokenCipher noKey = new TokenCipher(props, new MockEnvironment());   // no key property set
        GithubImporter importer = new GithubImporter(props,
                new GithubGitClient(props, writeFakeGit("git-ok", false)),
                configParser, registry, orchestrator, tracker, noKey);

        assertThatThrownBy(() -> importer.importFromUrl("https://github.com/foo/bar", "ghp_secret123"))
                .isInstanceOf(TokenCipherException.class)
                .hasMessageContaining("PRJXP_HUB_GITHUB_TOKEN_KEY");

        assertThat(Path.of(props.getGithubRoot(), "bar", ".git-clone-ran")).doesNotExist();   // no clone attempted
    }

    @Test
    void importFromUrlAsyncWithTokenButNoKeyRecordsFailedTrackerOp() throws Exception {
        TokenCipher noKey = new TokenCipher(props, new MockEnvironment());   // no key property set
        GithubImporter importer = new GithubImporter(props,
                new GithubGitClient(props, writeFakeGit("git-ok", false)),
                configParser, registry, orchestrator, tracker, noKey);

        importer.importFromUrlAsync("https://github.com/foo/bar", "ghp_secret123");

        awaitTrackerState(tracker, "https://github.com/foo/bar", GithubOperation.FAILED);
        assertThat(tracker.latest("https://github.com/foo/bar").orElseThrow().message())
                .contains("PRJXP_HUB_GITHUB_TOKEN_KEY");
    }

    // ------------------------------------------------------------------ Phase 09: name collision

    @Test
    void importFromUrl_withExistingProject_throwsAndDoesNotClone() throws Exception {
        // A same-name project already registered at a DIFFERENT rootDir (e.g. a snapshot import)
        Path otherDir = tempDir.resolve("projects").resolve("upe");
        when(registry.entry("upe")).thenReturn(Optional.of(
                new ProjectEntry("upe", otherDir, ProjectEntry.Kind.SNAPSHOT, "snapshot",
                        new ProjectDefinition(), null)));
        GithubImporter importer = importerWith(writeFakeGit("git-fail", true));   // a clone attempt would fail

        assertThatThrownBy(() -> importer.importFromUrl("https://github.com/foo/upe"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("upe")
                .hasMessageContaining("delete");

        Path dir = Path.of(props.getGithubRoot(), "upe");
        assertThat(dir).doesNotExist();   // no clone dir was created (no orphan on the /github volume)
        assertThat(dir.resolve(".git-clone-ran")).doesNotExist();   // the fake git was never invoked
        verify(orchestrator, never()).enqueueForced("upe");
    }

    @Test
    void importFromUrlAsync_withExistingProject_recordsFailedTrackerOp() throws Exception {
        Path otherDir = tempDir.resolve("projects").resolve("upe");
        when(registry.entry("upe")).thenReturn(Optional.of(
                new ProjectEntry("upe", otherDir, ProjectEntry.Kind.SNAPSHOT, "snapshot",
                        new ProjectDefinition(), null)));
        GithubImporter importer = importerWith(writeFakeGit("git-fail", true));   // a clone attempt would fail

        importer.importFromUrlAsync("https://github.com/foo/upe");

        awaitTrackerState(tracker, "https://github.com/foo/upe", GithubOperation.FAILED);
        assertThat(tracker.latest("https://github.com/foo/upe").orElseThrow().message())
                .contains("upe")
                .contains("delete");
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
