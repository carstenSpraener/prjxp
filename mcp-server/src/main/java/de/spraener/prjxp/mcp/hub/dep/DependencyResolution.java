package de.spraener.prjxp.mcp.hub.dep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The raw resolved set + the shared embeddable post-processing (dedup/depth/whitelist/classifier). */
public class DependencyResolution {
    private final List<RawDependency> raw;
    private final DependencyResolveConfig config;

    public DependencyResolution(List<RawDependency> raw, DependencyResolveConfig config) {
        this.raw = List.copyOf(raw);
        this.config = config;
    }
    public List<RawDependency> raw() { return raw; }
    public DependencyResolveConfig config() { return config; }

    /**
     * Shared filter: dedup by GAV (keep shallowest), bound by maxDepth, apply the GroupId
     * whitelist, then probe Maven Central for the -sources / -javadoc classifiers. Returns only
     * embeddable dependencies (at least one classifier present).
     */
    public List<ResolvedDependency> toEmbeddable(MavenCentralClient client) throws MavenDependencyException {
        Map<String, RawDependency> byGav = new LinkedHashMap<>();
        for (RawDependency d : raw) {
            RawDependency existing = byGav.get(d.coords().gav());
            if (existing == null || d.depth() < existing.depth()) {
                byGav.put(d.coords().gav(), d);   // keep shallowest
            }
        }
        List<ResolvedDependency> out = new ArrayList<>();
        for (RawDependency d : byGav.values()) {
            if (d.depth() > config.maxDepth()) continue;
            if (!config.isWhitelisted(d.coords().groupId())) continue;
            boolean hasSources = client.exists(d.coords(), "sources");
            boolean hasJavadoc = client.exists(d.coords(), "javadoc");
            ResolvedDependency rd = new ResolvedDependency(d.coords(), d.depth(), hasSources, hasJavadoc);
            if (rd.isEmbeddable()) {
                out.add(rd);
            }
        }
        return out;
    }
}
