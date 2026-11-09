package de.spraener.prjxp.mcp.hub.github;

import de.spraener.prjxp.mcp.hub.HubProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 02: {@link GithubGitClient} — runs a fake {@code git} executable
 * (a shell script in a temp dir, injected via the test constructor) so no real
 * git binary and no network are involved.
 */
class GithubGitClientTest {

    private static final String URL = "https://github.com/example/repo.git";

    @TempDir
    Path tempDir;

    private Path writeExecutable(String name, String script) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, script);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    private GithubGitClient clientWith(Path gitExecutable, int timeoutSeconds) {
        HubProperties props = new HubProperties();
        props.setGithubCloneDepth(1);
        props.setGithubTimeoutSeconds(timeoutSeconds);
        return new GithubGitClient(props, gitExecutable);
    }

    private static List<String> logLines(Path logFile) throws IOException {
        return Files.exists(logFile) ? Files.readAllLines(logFile) : List.of();
    }

    @Test
    void cloneInvokesGitCloneWithDepthUrlAndTargetDir() throws Exception {
        Path logFile = tempDir.resolve("git-args.log");
        Path fakeGit = writeExecutable("git", """
                #!/bin/sh
                echo "$@" >> "%s"
                for last; do :; done
                mkdir -p "$last"
                echo sentinel > "$last/.git-cloned"
                """.formatted(logFile));
        Path targetDir = tempDir.resolve("clones").resolve("repo");

        clientWith(fakeGit, 30).clone(URL, targetDir);

        assertThat(targetDir).exists();
        assertThat(targetDir.resolve(".git-cloned")).exists();
        assertThat(logLines(logFile)).containsExactly(
                "clone --depth 1 -- " + URL + " " + targetDir);
    }

    @Test
    void cloneCreatesMissingParentDirectory() throws Exception {
        Path fakeGit = writeExecutable("git", """
                #!/bin/sh
                for last; do :; done
                mkdir -p "$last"
                """);
        Path targetDir = tempDir.resolve("a").resolve("b").resolve("repo");

        clientWith(fakeGit, 30).clone(URL, targetDir);

        assertThat(targetDir).exists();
    }

    @Test
    void cloneWithNullUrlThrows() {
        assertThatThrownBy(() -> clientWith(Path.of("git"), 30).clone(null, tempDir.resolve("repo")))
                .isInstanceOf(GithubGitException.class)
                .hasMessageContaining("No git URL");
    }

    @Test
    void cloneNonZeroExitThrowsWithExitCodeAndOutputTail() throws Exception {
        Path fakeGit = writeExecutable("git", """
                #!/bin/sh
                echo "fatal: repository not found"
                exit 3
                """);

        assertThatThrownBy(() -> clientWith(fakeGit, 30).clone(URL, tempDir.resolve("repo")))
                .isInstanceOf(GithubGitException.class)
                .hasMessageContaining("exited with code 3")
                .hasMessageContaining("repository not found");
    }

    @Test
    void cloneTimeoutThrowsAndDestroysProcess() throws Exception {
        Path pidFile = tempDir.resolve("git.pid");
        // Close BOTH fds: with redirectErrorStream(true) stderr shares the stdout pipe,
        // so the parent's readAllBytes only sees EOF once the script drops both.
        Path fakeGit = writeExecutable("git", """
                #!/bin/sh
                echo $$ > "%s"
                exec 1>/dev/null 2>/dev/null
                sleep 5
                """.formatted(pidFile));

        long start = System.nanoTime();
        assertThatThrownBy(() -> clientWith(fakeGit, 1).clone(URL, tempDir.resolve("repo")))
                .isInstanceOf(GithubGitException.class)
                .hasMessageContaining("timed out");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(elapsedMs).isLessThan(4_000);   // bounded by the 1s timeout — no hang

        long pid = Long.parseLong(Files.readString(pidFile).trim());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            Thread.sleep(50);
        }
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                .as("fake git process %d must be destroyed", pid)
                .isFalse();
    }

    @Test
    void cloneWithMissingExecutableThrows() {
        assertThatThrownBy(() -> clientWith(tempDir.resolve("no-such-git"), 30)
                .clone(URL, tempDir.resolve("repo")))
                .isInstanceOf(GithubGitException.class)
                .hasMessageContaining("Failed to run git")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void pullInvokesGitPullFfOnlyInDirectory() throws Exception {
        Path logFile = tempDir.resolve("git-args.log");
        Path fakeGit = writeExecutable("git", """
                #!/bin/sh
                echo "$@" >> "%s"
                """.formatted(logFile));
        Path dir = tempDir.resolve("repo");
        Files.createDirectories(dir);

        clientWith(fakeGit, 30).pull(dir);

        assertThat(logLines(logFile)).containsExactly("-C " + dir + " pull --ff-only");
    }

    // ------------------------------------------------------------------ Phase 06: PAT via http.extraheader

    @Test
    void cloneWithTokenPassesExtraheaderAfterExecutable() throws Exception {
        Path logFile = tempDir.resolve("git-args.log");
        Path fakeGit = writeExecutable("git", """
                #!/bin/sh
                echo "$@" >> "%s"
                for last; do :; done
                mkdir -p "$last"
                """.formatted(logFile));
        Path targetDir = tempDir.resolve("clones").resolve("repo");

        clientWith(fakeGit, 30).clone(URL, targetDir, "ghp_secret123");

        assertThat(logLines(logFile)).containsExactly(
                "-c http.extraheader=Authorization: Bearer ghp_secret123 clone --depth 1 -- " + URL + " " + targetDir);
    }

    @Test
    void cloneWithNullTokenHasNoExtraheader() throws Exception {
        Path logFile = tempDir.resolve("git-args.log");
        Path fakeGit = writeExecutable("git", """
                #!/bin/sh
                echo "$@" >> "%s"
                for last; do :; done
                mkdir -p "$last"
                """.formatted(logFile));
        Path targetDir = tempDir.resolve("clones").resolve("repo");

        clientWith(fakeGit, 30).clone(URL, targetDir, null);

        assertThat(logLines(logFile)).containsExactly(
                "clone --depth 1 -- " + URL + " " + targetDir);   // unchanged 2-arg command shape
    }

    @Test
    void twoArgCloneHasNoExtraheader() throws Exception {
        Path logFile = tempDir.resolve("git-args.log");
        Path fakeGit = writeExecutable("git", """
                #!/bin/sh
                echo "$@" >> "%s"
                for last; do :; done
                mkdir -p "$last"
                """.formatted(logFile));
        Path targetDir = tempDir.resolve("clones").resolve("repo");

        clientWith(fakeGit, 30).clone(URL, targetDir);   // the existing 2-arg delegate

        assertThat(logLines(logFile)).noneMatch(line -> line.contains("http.extraheader"));
    }

    @Test
    void pullWithTokenPassesExtraheaderAfterExecutable() throws Exception {
        Path logFile = tempDir.resolve("git-args.log");
        Path fakeGit = writeExecutable("git", """
                #!/bin/sh
                echo "$@" >> "%s"
                """.formatted(logFile));
        Path dir = tempDir.resolve("repo");
        Files.createDirectories(dir);

        clientWith(fakeGit, 30).pull(dir, "ghp_secret123");

        assertThat(logLines(logFile)).containsExactly(
                "-c http.extraheader=Authorization: Bearer ghp_secret123 -C " + dir + " pull --ff-only");
    }

    @Test
    void pullWithNullTokenHasNoExtraheader() throws Exception {
        Path logFile = tempDir.resolve("git-args.log");
        Path fakeGit = writeExecutable("git", """
                #!/bin/sh
                echo "$@" >> "%s"
                """.formatted(logFile));
        Path dir = tempDir.resolve("repo");
        Files.createDirectories(dir);

        clientWith(fakeGit, 30).pull(dir, null);

        assertThat(logLines(logFile)).noneMatch(line -> line.contains("http.extraheader"));
    }

    @Test
    void pullNonZeroExitThrows() throws Exception {
        Path fakeGit = writeExecutable("git", """
                #!/bin/sh
                echo "fatal: not a git repository"
                exit 2
                """);
        Path dir = tempDir.resolve("repo");
        Files.createDirectories(dir);

        assertThatThrownBy(() -> clientWith(fakeGit, 30).pull(dir))
                .isInstanceOf(GithubGitException.class)
                .hasMessageContaining("exited with code 2")
                .hasMessageContaining("not a git repository");
    }

    @Test
    void tailReturnsWholeOutputWhenAtMost200Chars() {
        assertThat(GithubGitClient.tail("x".repeat(200))).isEqualTo("x".repeat(200));
        assertThat(GithubGitClient.tail("  short  ")).isEqualTo("short");
        assertThat(GithubGitClient.tail(null)).isEmpty();
    }

    @Test
    void tailTruncatesToLast200Chars() {
        String output = "a".repeat(150) + "b".repeat(100);   // 250 chars

        assertThat(GithubGitClient.tail(output)).isEqualTo("a".repeat(100) + "b".repeat(100));
    }
}
