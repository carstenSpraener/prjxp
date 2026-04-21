package de.spraener.prjxp.chuno.code.java;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import de.spraener.prjxp.chuno.spring.SpringPreWalkEvent;
import de.spraener.prjxp.common.config.PrjXPConfig;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JavaFQNamesMapper {
    private Map<String, Set<String>> name2FQNamesMap = new ConcurrentHashMap<>();

    @EventListener(SpringPreWalkEvent.class)
    public void fillFQNamesMap(SpringPreWalkEvent<PrjXPConfig> event) {
        try {
            Files.walk(Path.of(event.config().getChunoRootDir()))
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> handleFile(path.toFile()));
        } catch (Exception e) {

        }
    }

    private void handleFile(File file) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            cu.findAll(TypeDeclaration.class).forEach(type -> {
                type.getFullyQualifiedName().ifPresent(fqn -> {
                    registerFQName(type.getNameAsString(), fqn.toString());
                });
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void registerFQName(String name, String fqn) {
        Set<String> fqName = name2FQNamesMap.computeIfAbsent(name, k -> Collections.synchronizedSet(new HashSet<>()));
        fqName.add(fqn);
    }

    public String getFQName(String name, String fromPkg, List<String> imports) {
        Set<String> fqNames = name2FQNamesMap.get(name);
        if (fqNames == null) return "";

        // 1. Suche in expliziten Imports
        for (String fqName : fqNames) {
            String fqPkg = fqName.substring(0, Math.max(0, fqName.length() - name.length() - 1));
            String wildcardImp = fqPkg + ".*";

            for (var imp : imports) {
                if (imp.equals(fqName) || imp.equals(wildcardImp)) {
                    return fqName;
                }
            }
        }

        // 2. Prüfung: Existiert die Klasse im selben Paket?
        String samePkgFQN = fromPkg.isEmpty() ? name : fromPkg + "." + name;
        if (fqNames.contains(samePkgFQN)) {
            return samePkgFQN;
        }
        return name;
    }
}
