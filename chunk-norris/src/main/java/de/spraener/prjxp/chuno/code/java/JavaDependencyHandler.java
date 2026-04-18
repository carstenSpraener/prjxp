package de.spraener.prjxp.chuno.code.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.Type;
import de.spraener.prjxp.chuno.util.DependencyRegistry;
import de.spraener.prjxp.chuno.util.DependencyRegistryManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JavaDependencyHandler {
    public static final String JAVA_DEPENDENCIES = "JAVA_DEPENDENCIES";

    private final DependencyRegistryManager dependencyRegistryManager;
    private final JavaFQNamesMapper fqNamesMapper;

    public void fillDependencies(CompilationUnit cu) {
        DependencyRegistry depReg = dependencyRegistryManager.get(JAVA_DEPENDENCIES);
        fillDependencies(cu.getPrimaryType().get(), cu, depReg);
    }

    private void fillDependencies(TypeDeclaration<?> type, CompilationUnit cu, DependencyRegistry depReg) {
        String sourceFQN = type.getFullyQualifiedName().orElse(type.getNameAsString());

        // 1. Imports als Basis-Abhängigkeiten
        cu.getImports().forEach(imp -> {
            String importedName = imp.getNameAsString();
            // Statische Imports oder Wildcards behandeln wir als eine Abhängigkeit auf den Root-Typ
            depReg.addDependency(sourceFQN, importedName);
        });

        // 2. Vererbung (Extends & Implements)
        if (type.isClassOrInterfaceDeclaration()) {
            var classDecl = type.asClassOrInterfaceDeclaration();
            classDecl.getExtendedTypes().forEach(et -> depReg.addDependency(sourceFQN, resolveFQName(et, cu)));
            classDecl.getImplementedTypes().forEach(it -> depReg.addDependency(sourceFQN, resolveFQName(it, cu)));
        }

        // 3. Felder (Member-Variablen)
        type.getFields().forEach(field -> {
            depReg.addDependency(sourceFQN, resolveFQName(field.getElementType(), cu));
        });

        // 4. Methoden-Signaturen (Parameter & Rückgabewerte)
        type.getMethods().forEach(method -> {
            depReg.addDependency(sourceFQN, method.getType().asString());
            method.getParameters().forEach(
                    param -> depReg.addDependency(sourceFQN, resolveFQName(param.getType(), cu))
            );
        });

        // 5. Innere Klassen rekursiv behandeln
        type.getMembers().stream()
                .filter(m -> m instanceof TypeDeclaration)
                .map(m -> (TypeDeclaration<?>) m)
                .forEach(subType -> fillDependencies(subType, cu, depReg));
    }

    private String resolveFQName(Type type, CompilationUnit cu) {
        if (!cu.getPackageDeclaration().isPresent()) {
            return type.asString();
        }
        return fqNamesMapper.getFQName(type.asString(),
                cu.getPackageDeclaration().get().getNameAsString(),
                cu.getImports().stream().map(imp -> imp.getNameAsString()).toList()
        );
    }
}
