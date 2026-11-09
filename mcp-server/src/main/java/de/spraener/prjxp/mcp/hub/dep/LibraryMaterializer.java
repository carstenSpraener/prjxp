package de.spraener.prjxp.mcp.hub.dep;

import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.mcp.hub.HubProperties;
import de.spraener.prjxp.mcp.hub.HubProjectRegistry;
import de.spraener.prjxp.mcp.hub.PipelineOrchestrator;
import de.spraener.prjxp.mcp.hub.ProjectConfigFileParser;
import de.spraener.prjxp.mcp.hub.ProjectEntry;
import de.spraener.prjxp.mcp.hub.source.LibrarySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Materializes one resolved dependency as an independent SNAPSHOT library project under
 * {@code librariesRoot} in Maven-repo layout: {@code <g-as-path>/<a>/<v>/{src,doc,prjxp.yaml}}.
 * Idempotent: a populated library (src/ or doc/ present) is skipped unless {@code force}.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
public class LibraryMaterializer {

    private static final Logger log = LoggerFactory.getLogger(LibraryMaterializer.class);

    private final HubProperties props;
    private final MavenCentralClient client;
    private final ProjectConfigFileParser configParser;
    private final HubProjectRegistry registry;
    private final PipelineOrchestrator orchestrator;

    public LibraryMaterializer(HubProperties props, MavenCentralClient client,
                               ProjectConfigFileParser configParser,
                               HubProjectRegistry registry, PipelineOrchestrator orchestrator) {
        this.props = props; this.client = client; this.configParser = configParser;
        this.registry = registry; this.orchestrator = orchestrator;
    }

    /** The library directory for a coordinate: {@code librariesRoot/<g-as-path>/<a>/<v>}. */
    public Path libraryDir(ResolvedDependency dep) {
        return Path.of(props.getLibrariesRoot(), dep.coords().repoPath().toString());
    }

    /** True when the library dir already has content (src/ or doc/ with files) — idempotency check. */
    public boolean isPopulated(ResolvedDependency dep) {
        Path dir = libraryDir(dep);
        for (String sub : new String[]{"src", "doc"}) {
            Path p = dir.resolve(sub);
            if (Files.isDirectory(p) && hasFiles(p)) return true;
        }
        return false;
    }

    private boolean hasFiles(Path dir) {
        try (var s = Files.list(dir)) { return s.findAny().isPresent(); }
        catch (IOException e) { return false; }
    }

    /**
     * Download + extract + generate yaml + register + enqueue. Idempotent (skips populated libs
     * unless force). Throws on download/extract failure — the caller (service) catches and skips.
     */
    public void materialize(ResolvedDependency dep, boolean force) throws MavenDependencyException {
        if (!force && isPopulated(dep)) {
            log.info("Library {} already materialized — skipping", dep.coords().gav());
            return;
        }
        Path dir = libraryDir(dep);
        try {
            Files.createDirectories(dir);
            if (dep.hasSources()) {
                Path tmp = Files.createTempFile("prjxp-lib-", "-sources.jar");
                try {
                    client.download(dep.coords(), "sources", tmp);
                    extractZip(tmp, dir.resolve("src"));
                } finally { Files.deleteIfExists(tmp); }
            }
            if (dep.hasJavadoc()) {
                Path tmp = Files.createTempFile("prjxp-lib-", "-javadoc.jar");
                try {
                    client.download(dep.coords(), "javadoc", tmp);
                    extractZip(tmp, dir.resolve("doc"));
                } finally { Files.deleteIfExists(tmp); }
            }
        } catch (IOException e) {
            throw new MavenDependencyException("Extraction failed for " + dep.coords().gav() + ": " + e.getMessage(), e);
        }
        generateYaml(dir, dep);
        String name = dep.coords().name();
        if (!registry.entry(name).isPresent()) {
            // Stamp the library source explicitly: a library is SNAPSHOT kind but must be tracked as
            // source "library" (not "snapshot") so the registry's source-based vanish filter is correct.
            registry.registerProject(name, dir, ProjectEntry.Kind.SNAPSHOT, LibrarySource.NAME);
        } else {
            registry.reloadDefinition(name);
        }
        orchestrator.enqueueForced(name);   // standard pipeline (no embeddingsFile)
        log.info("Materialized library {} at {}", name, dir);
    }

    /** Generate the library's prjxp.yaml: name, rootDir ".", dynamic whitelist, mavenDependency marker. */
    private void generateYaml(Path dir, ResolvedDependency dep) {
        ProjectDefinition def = new ProjectDefinition();
        def.setName(dep.coords().name());
        def.setRootDir(".");
        def.setJsonlFile("px-chunks.jsonl");
        def.setChunoWhiteList(whitelistFor(dep));
        def.setMavenDependency(dep.coords().gav());   // origin marker
        configParser.save(dir, def);
    }

    /** Dynamic whitelist: java for src/, html for doc/ (at least one is present by the embeddable filter). */
    private String whitelistFor(ResolvedDependency dep) {
        if (dep.hasSources() && dep.hasJavadoc()) return "java,html";
        if (dep.hasSources()) return "java";
        return "html";
    }

    /**
     * Extract a zip into {@code target} with security validation (two-pass, mirroring
     * {@code EmbeddedArchiveImporter.extractZip}):
     * <ol>
     *   <li>Pass 1 — validate every entry (reject absolute paths, {@code ..} segments)
     *       and compute a single common root segment to strip (when all entries share one).</li>
     *   <li>Pass 2 — extract, re-checking each resolved path stays inside {@code target} and the
     *       cumulative written size stays under the limit.</li>
     * </ol>
     */
    private void extractZip(Path zipFile, Path target) throws IOException, MavenDependencyException {
        if (Files.size(zipFile) > props.getMaxDependencyBytes()) {
            throw new MavenDependencyException("Archive exceeds size limit: " + zipFile);
        }
        Files.createDirectories(target);
        Path normalizedTarget = target.toAbsolutePath().normalize();

        // Pass 1: validate + compute the single-root strip prefix.
        String stripPrefix = null;
        String commonFirst = null;
        boolean allSharePrefix = true;
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                validateEntry(entry.getName());
                int slash = entry.getName().indexOf('/');
                if (slash < 0) {
                    allSharePrefix = false;
                } else {
                    String first = entry.getName().substring(0, slash);
                    if (commonFirst == null) commonFirst = first;
                    else if (!commonFirst.equals(first)) allSharePrefix = false;
                }
            }
        }
        if (allSharePrefix && commonFirst != null) {
            stripPrefix = commonFirst + "/";
        }

        // Pass 2: extract.
        long written = 0;
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = strip(entry.getName(), stripPrefix);
                Path resolved = target.resolve(name).toAbsolutePath().normalize();
                if (!resolved.startsWith(normalizedTarget)) {
                    throw new MavenDependencyException("Zip entry escapes target: " + entry.getName());
                }
                Files.createDirectories(resolved.getParent());
                byte[] buf = zin.readAllBytes();
                written += buf.length;
                if (written > props.getMaxDependencyBytes()) {
                    throw new MavenDependencyException("Extracted content exceeds size limit");
                }
                Files.write(resolved, buf);
            }
        }
    }

    private void validateEntry(String name) throws MavenDependencyException {
        if (name.startsWith("/") || name.startsWith("\\") || name.startsWith("\\\\")) {
            throw new MavenDependencyException("Zip entry with absolute path: " + name);
        }
        for (String segment : name.split("/")) {
            if ("..".equals(segment)) throw new MavenDependencyException("Zip entry with '..': " + name);
        }
    }

    private String strip(String name, String prefix) {
        return (prefix != null && name.startsWith(prefix)) ? name.substring(prefix.length()) : name;
    }
}
