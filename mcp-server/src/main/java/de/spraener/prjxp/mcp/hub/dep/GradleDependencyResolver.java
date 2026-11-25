package de.spraener.prjxp.mcp.hub.dep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Resolves a Gradle project's dependencies. Primary: run {@code gradle dependencies}
 * (JDK + Gradle are available in the hub container) and parse the tree output.
 * Fallback (when the process fails): {@link BuildGradleParser} regex (direct deps only).
 */
public class GradleDependencyResolver implements DependencyResolver {

    private static final Logger log = LoggerFactory.getLogger(GradleDependencyResolver.class);

    private final BuildGradleParser parser;
    private final int gradleTimeoutSeconds;
    private final List<String> gradleExecutable;

    /** Production: uses {@code gradle} from the PATH. */
    public GradleDependencyResolver(BuildGradleParser parser, int gradleTimeoutSeconds) {
        this(parser, gradleTimeoutSeconds, Path.of("gradle"));
    }

    /** Testable: explicit executable (e.g. a fake {@code gradle} script in a temp dir). */
    public GradleDependencyResolver(BuildGradleParser parser, int gradleTimeoutSeconds, Path gradleExecutable) {
        this(parser, gradleTimeoutSeconds, List.of(gradleExecutable.toString()));
    }

    GradleDependencyResolver(BuildGradleParser parser, int gradleTimeoutSeconds, List<String> gradleExecutable) {
        this.parser = parser;
        this.gradleTimeoutSeconds = gradleTimeoutSeconds;
        this.gradleExecutable = List.copyOf(gradleExecutable);
    }

    @Override
    public boolean supports(Path projectRoot) {
        return Files.exists(projectRoot.resolve("build.gradle"))
                || Files.exists(projectRoot.resolve("build.gradle.kts"));
    }

    @Override
    public DependencyResolution resolve(Path projectRoot, DependencyResolveConfig config) throws MavenDependencyException {
        List<RawDependency> raw;
        try {
            String output = runGradleDependencies(projectRoot);
            raw = parseTree(output);
        } catch (MavenDependencyException e) {
            log.warn("`gradle dependencies` failed for {} — falling back to build.gradle parsing: {}",
                    projectRoot, e.getMessage());
            raw = parser.parse(projectRoot);   // depth 1 only
        }
        return new DependencyResolution(raw, config);
    }

    /** Run {@code gradle -q dependencies --configuration runtimeClasspath --console plain}. */
    private String runGradleDependencies(Path projectRoot) throws MavenDependencyException {
        try {
            List<String> command = new ArrayList<>(gradleExecutable);
            command.addAll(List.of("-q", "dependencies", "--configuration", "runtimeClasspath", "--console", "plain"));
            ProcessBuilder pb = new ProcessBuilder(command)
                    .directory(projectRoot.toFile())
                    .redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(gradleTimeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new MavenDependencyException("gradle dependencies timed out after " + gradleTimeoutSeconds + "s");
            }
            if (p.exitValue() != 0) {
                throw new MavenDependencyException("gradle dependencies exited with code " + p.exitValue());
            }
            return output;
        } catch (IOException e) {
            throw new MavenDependencyException("Failed to run gradle: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MavenDependencyException("Interrupted while running gradle", e);
        }
    }

    /**
     * Parse the Gradle dependency-tree output. A line's depth is derived from the column of its
     * {@code +---} / {@code \---} marker (each nesting level indents by 4): depth = markerCol / 4 + 1.
     * Version constraints in parentheses (e.g. {@code (version)}) are stripped.
     */
    List<RawDependency> parseTree(String output) {
        List<RawDependency> deps = new ArrayList<>();
        for (String line : output.split("\n")) {
            int plus = line.indexOf("+---");
            int backslash = line.indexOf("\\---");
            int marker = Math.min(plus >= 0 ? plus : Integer.MAX_VALUE,
                                  backslash >= 0 ? backslash : Integer.MAX_VALUE);
            if (marker == Integer.MAX_VALUE) {
                continue;   // not a dependency line (e.g. the "runtimeClasspath" header)
            }
            int depth = marker / 4 + 1;
            String gav = line.substring(marker + 4).trim();
            gav = gav.replaceAll("\\s*\\(.*\\)$", "").trim();   // strip "(constraint)" suffixes
            String[] parts = gav.split(":");
            if (parts.length < 3) {
                continue;   // malformed — skip
            }
            deps.add(new RawDependency(new MavenCoordinates(parts[0], parts[1], parts[2]), depth));
        }
        return deps;
    }
}
