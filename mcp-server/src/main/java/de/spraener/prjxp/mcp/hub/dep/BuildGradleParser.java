package de.spraener.prjxp.mcp.hub.dep;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fallback parser for Gradle build files: extracts direct dependencies declared as
 * {@code implementation "g:a:v"} / {@code api "g:a:v"} / {@code compile "g:a:v"} /
 * {@code runtimeOnly "g:a:v"} (Groovy) or {@code implementation("g:a:v")} (Kotlin).
 * All results are depth 1 (no transitive resolution without running Gradle).
 */
public class BuildGradleParser {

    /** Matches both Groovy (implementation "g:a:v") and Kotlin (implementation("g:a:v")) forms. */
    private static final Pattern DEP = Pattern.compile(
            "(?:implementation|api|compile|runtimeOnly)\\s*\\(?\\s*[\"']([^\"':\\s]+):([^\"':\\s]+):([^\"'\\s]+)[\"']");

    /** Parse the project's build.gradle / build.gradle.kts into depth-1 dependencies. */
    public List<RawDependency> parse(Path projectRoot) {
        List<RawDependency> out = new ArrayList<>();
        for (String fileName : new String[]{"build.gradle", "build.gradle.kts"}) {
            Path file = projectRoot.resolve(fileName);
            if (!Files.exists(file)) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(file)) {
                    Matcher m = DEP.matcher(line);
                    while (m.find()) {
                        out.add(new RawDependency(
                                new MavenCoordinates(m.group(1), m.group(2), m.group(3)), 1));
                    }
                }
            } catch (IOException ignored) {
                // unreadable build file — contribute nothing
            }
        }
        return out;
    }
}
