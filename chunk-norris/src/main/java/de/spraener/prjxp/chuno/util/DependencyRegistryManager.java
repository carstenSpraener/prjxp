package de.spraener.prjxp.chuno.util;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class DependencyRegistryManager {
    private final Environment env;

    private Map<String, DependencyRegistry> dependencyRegistryMap = new java.util.HashMap<>();

    public DependencyRegistry get(String name) {
        var dependencyRegistry = dependencyRegistryMap.get(name);
        if (dependencyRegistry == null) {
            String dependencyPrefix = env.getProperty("dependencyPrefix." + name, "de.spraener");
            dependencyRegistry = new DependencyRegistry(name, dependencyPrefix);
            dependencyRegistryMap.put(name, dependencyRegistry);
        }
        return dependencyRegistry;
    }
}
