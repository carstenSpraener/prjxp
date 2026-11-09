package de.spraener.prjxp.mcp.hub.github;

import de.spraener.prjxp.mcp.hub.HubProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper around the {@code git} executable: shallow-clone a repository and pull updates.
 * Shells out via {@link ProcessBuilder} (mirrors {@code GradleDependencyResolver}) — no JGit
 * dependency. The executable path is constructor-injected so tests can point it at a fake script.
 * Requires {@code git} on the host.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
public class GithubGitClient {

    private static final Logger log = LoggerFactory.getLogger(GithubGitClient.class);

    private final HubProperties props;
    private final Path gitExecutable;

    /** Production: uses the {@code git} on PATH. */
    @Autowired
    public GithubGitClient(HubProperties props) {
        this(props, Path.of("git"));
    }

    /** Test hook: inject a fake {@code git} executable. */
    public GithubGitClient(HubProperties props, Path gitExecutable) {
        this.props = props;
        this.gitExecutable = gitExecutable;
    }

    /**
     * Shallow-clone {@code url} into {@code targetDir} (parent created). The target must not already
     * exist as a directory — the caller checks idempotency. Throws on any git failure/timeout.
     */
    public void clone(String url, Path targetDir) throws GithubGitException {
        clone(url, targetDir, null);
    }

    /**
     * Shallow-clone {@code url} into {@code targetDir} with an optional PAT. A non-blank {@code token}
     * is passed out-of-band via {@code -c http.extraheader=Authorization: Bearer <token>} (never in the
     * URL, never in a log statement). Throws on any git failure/timeout.
     */
    public void clone(String url, Path targetDir, String token) throws GithubGitException {
        if (url == null) {
            throw new GithubGitException("No git URL");
        }
        try {
            Files.createDirectories(targetDir.getParent());
        } catch (IOException e) {
            throw new GithubGitException("Could not create parent of " + targetDir + ": " + e.getMessage(), e);
        }
        run("clone", command(token, "clone", "--depth", String.valueOf(props.getGithubCloneDepth()),
                "--", url, targetDir.toString()));
        log.info("Cloned {} into {}", url, targetDir);
    }

    /**
     * {@code git pull} inside an existing clone at {@code dir}. Throws on any git failure/timeout.
     */
    public void pull(Path dir) throws GithubGitException {
        pull(dir, null);
    }

    /**
     * {@code git pull} inside an existing clone at {@code dir} with an optional PAT (see
     * {@link #clone(String, Path, String)}). Throws on any git failure/timeout.
     */
    public void pull(Path dir, String token) throws GithubGitException {
        run("pull", command(token, "-C", dir.toString(), "pull", "--ff-only"));
        log.info("Pulled {}", dir);
    }

    /**
     * Builds the git command: the executable, then — when {@code token} is non-blank — the
     * {@code -c http.extraheader=Authorization: Bearer <token>} pair right after it (before the verb),
     * then the given arguments. The token is a process argument only; it is never logged.
     */
    private String[] command(String token, String... args) {
        List<String> command = new ArrayList<>();
        command.add(gitExecutable.toString());
        if (token != null && !token.isBlank()) {
            command.add("-c");
            command.add("http.extraheader=Authorization: Bearer " + token);
        }
        for (String arg : args) {
            command.add(arg);
        }
        return command.toArray(new String[0]);
    }

    /** Shared runner: start the process, read all output before waiting, enforce the timeout. */
    private void run(String verb, String[] command) throws GithubGitException {
        try {
            ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(props.getGithubTimeoutSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new GithubGitException("git " + verb + " timed out after "
                        + props.getGithubTimeoutSeconds() + "s");
            }
            if (p.exitValue() != 0) {
                throw new GithubGitException("git " + verb + " exited with code " + p.exitValue()
                        + ": " + tail(output));
            }
        } catch (IOException e) {
            throw new GithubGitException("Failed to run git: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GithubGitException("Interrupted while running git", e);
        }
    }

    /** Keep exception messages bounded: last ~200 chars of the (possibly long) git output. */
    static String tail(String output) {
        if (output == null) return "";
        String trimmed = output.trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(trimmed.length() - 200);
    }
}
