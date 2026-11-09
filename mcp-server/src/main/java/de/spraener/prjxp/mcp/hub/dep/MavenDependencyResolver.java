package de.spraener.prjxp.mcp.hub.dep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a Maven project's dependency tree via a bounded BFS over POMs fetched from Maven Central.
 * Direct dependencies are depth 1; their transitive deps are depth 2, etc. BFS stops expanding at
 * {@code maxDepth}. Test-scope dependencies are excluded. Unresolvable versions are skipped with a warning.
 */
public class MavenDependencyResolver implements DependencyResolver {

    private static final Logger log = LoggerFactory.getLogger(MavenDependencyResolver.class);

    private final MavenCentralClient client;
    private final MavenPomParser parser;

    public MavenDependencyResolver(MavenCentralClient client, MavenPomParser parser) {
        this.client = client;
        this.parser = parser;
    }

    @Override
    public boolean supports(Path projectRoot) {
        return Files.exists(projectRoot.resolve("pom.xml"));
    }

    @Override
    public DependencyResolution resolve(Path projectRoot, DependencyResolveConfig config) throws MavenDependencyException {
        Path pomFile = projectRoot.resolve("pom.xml");
        if (!Files.exists(pomFile)) {
            return new DependencyResolution(List.of(), config);
        }
        MavenPomParser.PomModel root;
        try {
            root = parser.parse(Files.readString(pomFile));
        } catch (IOException e) {
            throw new MavenDependencyException("Cannot read " + pomFile + ": " + e.getMessage(), e);
        }

        Map<String, Integer> seen = new LinkedHashMap<>();   // gav -> shallowest depth
        Deque<RawDependency> queue = new ArrayDeque<>();
        for (MavenPomParser.DepRef dep : root.dependencies()) {
            enqueue(dep, root, 1, seen, queue);
        }

        List<RawDependency> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            RawDependency cur = queue.poll();
            result.add(cur);
            if (cur.depth() >= config.maxDepth()) {
                continue;   // do not expand beyond the bound
            }
            MavenPomParser.PomModel pom;
            try {
                pom = parser.parse(client.fetchPom(cur.coords()));
            } catch (MavenDependencyException e) {
                log.warn("Skipping {} — cannot fetch its POM: {}", cur.coords().gav(), e.getMessage());
                continue;   // one bad POM must not break the rest
            }
            for (MavenPomParser.DepRef dep : pom.dependencies()) {
                enqueue(dep, pom, cur.depth() + 1, seen, queue);
            }
        }
        return new DependencyResolution(result, config);
    }

    private void enqueue(MavenPomParser.DepRef dep, MavenPomParser.PomModel owner, int depth,
                         Map<String, Integer> seen, Deque<RawDependency> queue) {
        if ("test".equals(dep.scope())) {
            return;   // test-scope is never embedded
        }
        MavenCoordinates c = toCoordinates(dep, owner);
        if (c == null) {
            return;   // unresolvable — already warned
        }
        if (seen.putIfAbsent(c.gav(), depth) == null) {
            queue.add(new RawDependency(c, depth));   // first (shallowest) sighting wins
        }
    }

    /** Build coordinates from a dep ref, resolving the version: explicit -> own depMgmt -> parent depMgmt. */
    private MavenCoordinates toCoordinates(MavenPomParser.DepRef dep, MavenPomParser.PomModel owner) {
        String groupId = dep.groupId() != null ? dep.groupId() : owner.groupId();
        String version = dep.version();
        if (version == null) {
            version = lookupDependencyManagement(owner, dep);
        }
        if (version == null && owner.parent() != null) {
            version = lookupParentDependencyManagement(owner, dep);
        }
        if (version == null) {
            log.warn("Unresolvable version for {}:{} — skipping", groupId, dep.artifactId());
            return null;
        }
        return new MavenCoordinates(groupId, dep.artifactId(), version);
    }

    private String lookupDependencyManagement(MavenPomParser.PomModel pom, MavenPomParser.DepRef dep) {
        return pom.dependencyManagement().stream()
                .filter(m -> m.groupId().equals(dep.groupId()) && m.artifactId().equals(dep.artifactId()))
                .map(MavenPomParser.DepRef::version)
                .findFirst().orElse(null);
    }

    private String lookupParentDependencyManagement(MavenPomParser.PomModel owner, MavenPomParser.DepRef dep) {
        MavenPomParser.ParentRef parent = owner.parent();
        if (parent == null || parent.version() == null) {
            return null;
        }
        try {
            MavenPomParser.PomModel parentPom = parser.parse(client.fetchPom(
                    new MavenCoordinates(parent.groupId(), parent.artifactId(), parent.version())));
            return lookupDependencyManagement(parentPom, dep);
        } catch (MavenDependencyException e) {
            log.warn("Cannot fetch parent POM {}:{}:{} — skipping version lookup: {}",
                    parent.groupId(), parent.artifactId(), parent.version(), e.getMessage());
            return null;
        }
    }
}
