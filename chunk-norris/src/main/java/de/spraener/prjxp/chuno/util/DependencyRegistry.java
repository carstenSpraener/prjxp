package de.spraener.prjxp.chuno.util;

import java.util.stream.Stream;

public class DependencyRegistry {
    private final java.util.Map<String, java.util.Set<String>> dependencies = new java.util.HashMap<>();
    private final java.util.Map<String, java.util.Set<String>> usedBy = new java.util.HashMap<>();
    private final String prefix;
    private final String name;

    DependencyRegistry(String name, String prefix) {
        this.name = name;
        this.prefix = prefix;
    }

    public synchronized void addDependency(String source, String target) {
        if (!target.startsWith(prefix)) {
            return;
        }
        dependencies.computeIfAbsent(source, k -> new java.util.HashSet<>()).add(target);
        usedBy.computeIfAbsent(target, k -> new java.util.HashSet<>()).add(source);
    }

    public java.util.Set<String> getDependencies(String source) {
        return dependencies.getOrDefault(source, java.util.Collections.emptySet());
    }

    public void clear() {
        dependencies.clear();
    }

    public Stream<String> keyStream() {
        return dependencies.keySet().stream();
    }

    public Stream<String> getUsedBy(String source) {
        return usedBy.getOrDefault(source, java.util.Collections.emptySet()).stream();
    }
}
