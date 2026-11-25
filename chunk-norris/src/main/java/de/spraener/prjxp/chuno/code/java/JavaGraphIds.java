package de.spraener.prjxp.chuno.code.java;

import com.github.javaparser.ast.type.Type;
import java.util.List;

/** Shared graph-node id rules (builder + chunker must produce identical ids). */
public final class JavaGraphIds {
    public static final String LANGUAGE = "java";
    private JavaGraphIds() {}

    public static String typeId(String fqn) { return fqn; }

    public static String methodId(String typeFqn, String name, List<String> paramFqns) {
        return typeFqn + "#" + name + "(" + String.join(",", paramFqns) + ")";
    }

    /** Source-level raw type name: generics erased to outer type, arrays unwrapped, null for var. */
    public static String rawTypeName(Type type) {
        if (type == null || type.isVarType() || type.isWildcardType()) return null;
        if (type.isClassOrInterfaceType()) return type.asClassOrInterfaceType().getNameAsString();
        if (type.isArrayType()) return rawTypeName(type.asArrayType().getComponentType());
        if (type.isPrimitiveType() || type.isVoidType()) return type.asString();
        return type.asString();
    }

    /**
     * First-level type arguments of a type ({@code List<Foo>} -> {@code [Foo]}). Arrays are
     * unwrapped once per dimension; nested generics are deliberately not expanded
     * (a single level is enough for the benchmark questions and avoids an edge explosion).
     */
    public static List<Type> typeArguments(Type type) {
        if (type == null) return List.of();
        if (type.isClassOrInterfaceType()) {
            var arguments = type.asClassOrInterfaceType().getTypeArguments();
            return arguments.isPresent() ? arguments.get() : List.of();
        }
        if (type.isArrayType()) return typeArguments(type.asArrayType().getComponentType());
        return List.of();
    }

    /** Mapper FQN resolution; blank/unknown -> the raw source name (never null when raw is non-blank). */
    public static String resolveTypeName(String raw, JavaFQNamesMapper mapper, String fromPkg, List<String> imports) {
        if (raw == null || raw.isBlank()) return null;
        String fq = mapper.getFQName(raw, fromPkg, imports);
        return (fq == null || fq.isBlank()) ? raw : fq;
    }

    public static String resolveTypeName(Type type, JavaFQNamesMapper mapper, String fromPkg, List<String> imports) {
        return resolveTypeName(rawTypeName(type), mapper, fromPkg, imports);
    }
}
