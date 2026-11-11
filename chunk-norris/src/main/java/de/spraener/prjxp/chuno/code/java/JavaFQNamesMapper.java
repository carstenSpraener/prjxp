package de.spraener.prjxp.chuno.code.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import de.spraener.prjxp.chuno.spring.SpringPreWalkEvent;
import de.spraener.prjxp.common.config.PrjXPConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
@Log
public class JavaFQNamesMapper {
    private final JavaParserFactory javaParserFactory;

    private Map<String, Set<String>> name2FQNamesMap = new ConcurrentHashMap<>();

    @Order(10)
    @EventListener(SpringPreWalkEvent.class)
    public void fillFQNamesMap(SpringPreWalkEvent<PrjXPConfig> event) {
        name2FQNamesMap.clear();
        AtomicInteger filesEncountered = new AtomicInteger();
        AtomicInteger registeredTypes = new AtomicInteger();
        try {
            Files.walk(Path.of(event.project().getRootDir()))
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        filesEncountered.incrementAndGet();
                        registeredTypes.addAndGet(handleFile(path.toFile()));
                    });
        } catch (Exception e) {
            log.warning("Could not fill FQNames map: " + e.getMessage());
        }
        if (filesEncountered.get() > 0 && registeredTypes.get() == 0) {
            log.warning("FQNames map is empty: all " + filesEncountered.get() + " .java files failed to parse. "
                    + "Type-level graph edges (EXTENDS/IMPLEMENTS/FIELD/RETURN/PARAM) will be unresolved/external.");
        } else if (filesEncountered.get() == 0) {
            log.info("No .java files found under " + event.project().getRootDir() + " (not a Java project or wrong rootDir?).");
        }
    }

    private int handleFile(File file) {
        try {
            CompilationUnit cu = javaParserFactory.parse(file);
            AtomicInteger count = new AtomicInteger();
            cu.findAll(TypeDeclaration.class).forEach(type -> {
                type.getFullyQualifiedName().ifPresent(fqn -> {
                    registerFQName(type.getNameAsString(), fqn.toString());
                    count.incrementAndGet();
                });
            });
            return count.get();
        } catch (Exception e) {
            log.warning("Could not parse file for FQNames map " + file + ": " + e.getMessage());
            return 0;
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
