package de.spraener.prjxp.mcp.hub.dep;

import java.util.Arrays;
import java.util.List;

/** Resolution bounds: max hops + GroupId-prefix whitelist (empty = no filter). */
public record DependencyResolveConfig(int maxDepth, List<String> groupIdPrefixes) {
    public boolean isWhitelisted(String groupId) {
        if (groupId == null) return false;
        if (groupIdPrefixes.isEmpty()) return true;   // empty = import everything
        return groupIdPrefixes.stream().anyMatch(p -> !p.isBlank() && groupId.startsWith(p));
    }
    public static DependencyResolveConfig fromDefinition(boolean embed, int depth, String whitelistCsv) {
        List<String> prefixes = whitelistCsv == null ? List.of()
                : Arrays.stream(whitelistCsv.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new DependencyResolveConfig(Math.max(1, depth), prefixes);
    }
}
