package de.spraener.prjxp.chuno.code.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.type.Type;
import de.spraener.prjxp.chuno.spring.SpringPreWalkEvent;
import de.spraener.prjxp.chuno.veto.VetoRegistry;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.language.CodeGraphBuilder;
import de.spraener.prjxp.common.language.GraphWeighter;
import de.spraener.prjxp.common.model.CodeGraph;
import de.spraener.prjxp.common.model.EdgeType;
import de.spraener.prjxp.common.model.GraphEdge;
import de.spraener.prjxp.common.model.GraphNode;
import de.spraener.prjxp.common.model.PxFileType;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the Java code knowledge graph during the pre-walk (AST → nodes/edges), then rates it
 * with a {@link GraphWeighter}. Driven by {@link SpringPreWalkEvent}; the result is exposed via
 * {@link #weightedGraph()} so the main walk can attach {@code leiden_*} metadata to chunks.
 */
@Component
@RequiredArgsConstructor
@Log
public class JavaCodeGraphBuilder implements CodeGraphBuilder {

    private final JavaFQNamesMapper fqNamesMapper;
    private final GraphWeighter graphWeighter;
    private final VetoRegistry vetoRegistry;
    private final JavaParserFactory javaParserFactory;

    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<PendingEdge> edges = new ArrayList<>();
    private final List<PendingCall> calls = new ArrayList<>();
    private final Map<String, Map<String, List<MethodInfo>>> methodIndex = new HashMap<>();
    private final Map<String, String> simpleNameToFqn = new HashMap<>();
    private Path rootDir;
    private CodeGraph weighted;

    private record PendingEdge(String source, String target, EdgeType type) {}
    private record PendingCall(String source, String receiverType, String methodName, int arity) {}
    private record MethodInfo(String id, List<String> paramFqns, String returnType) {
        int arity() { return paramFqns.size(); }
    }
    private record Context(String pkg, List<String> imports, String relFile) {}

    /** Sets the project root used to compute project-relative file paths (tests call this). */
    public void setRootDir(Path rootDir) {
        this.rootDir = rootDir.toAbsolutePath().normalize();
    }

    @Override
    public void beginRun() {
        nodes.clear();
        edges.clear();
        calls.clear();
        methodIndex.clear();
        simpleNameToFqn.clear();
        weighted = null;
        // rootDir is intentionally NOT cleared: it is set once per run via setRootDir/onPreWalk.
    }

    @Override
    public boolean matches(File f) {
        return PxFileType.JAVA_CODE.matches(f);
    }

    @Override
    public Optional<CodeGraph> weightedGraph() {
        return Optional.ofNullable(weighted);
    }

    @Order(20)
    @EventListener(SpringPreWalkEvent.class)
    public void onPreWalk(SpringPreWalkEvent<PrjXPConfig> event) {
        ProjectDefinition pd = event.project();
        beginRun();
        this.rootDir = Path.of(pd.getRootDir()).toAbsolutePath().normalize();
        try {
            Files.walk(rootDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> matches(p.toFile()))
                    .filter(p -> !vetoRegistry.shouldVeto(p, pd))
                    .sorted()
                    .forEach(p -> process(p.toFile()));
        } catch (Exception e) {
            // Never throw out of an event listener: a walk failure degrades to an empty graph.
            log.warning("Could not walk project root for code graph " + rootDir + ": " + e.getMessage());
        }
        CodeGraph graph = build();
        this.weighted = graphWeighter.apply(graph);
    }

    @Override
    public void process(File f) {
        try {
            CompilationUnit cu = javaParserFactory.parse(f);
            String relFile = relFile(f);
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            List<String> imports = cu.getImports().stream().map(i -> i.getNameAsString()).toList();
            Context ctx = new Context(pkg, imports, relFile);
            for (TypeDeclaration<?> t : collectTypes(cu)) {
                String fqn = fqnOf(t);
                nodes.add(GraphNode.type(fqn, relFile, JavaGraphIds.LANGUAGE));
                simpleNameToFqn.putIfAbsent(t.getNameAsString(), fqn);
                for (ImportDeclaration imp : cu.getImports()) {
                    String target = importTarget(imp);
                    if (target != null) edges.add(new PendingEdge(fqn, target, EdgeType.IMPORT));
                }
                if (t.isClassOrInterfaceDeclaration()) {
                    t.asClassOrInterfaceDeclaration().getExtendedTypes()
                            .forEach(ct -> addResolvedEdge(fqn, ct, EdgeType.EXTENDS, ctx));
                    t.asClassOrInterfaceDeclaration().getImplementedTypes()
                            .forEach(ct -> addResolvedEdge(fqn, ct, EdgeType.IMPLEMENTS, ctx));
                }
                if (t.isEnumDeclaration()) {
                    t.asEnumDeclaration().getImplementedTypes()
                            .forEach(ct -> addResolvedEdge(fqn, ct, EdgeType.IMPLEMENTS, ctx));
                }
                if (t.isRecordDeclaration()) {
                    t.asRecordDeclaration().getImplementedTypes()
                            .forEach(ct -> addResolvedEdge(fqn, ct, EdgeType.IMPLEMENTS, ctx));
                }
                for (FieldDeclaration fd : t.getFields()) {
                    addResolvedEdge(fqn, fd.getElementType(), EdgeType.FIELD, ctx);
                }
                // Register ALL methods/constructors first (so intra-class chain calls resolve),
                // then collect calls.
                for (MethodDeclaration m : t.getMethods()) registerMethod(fqn, m, ctx);
                for (ConstructorDeclaration c : t.getConstructors()) registerConstructor(fqn, c, ctx);
                for (MethodDeclaration m : t.getMethods()) collectCalls(methodIdOf(fqn, m, ctx), t, m, ctx);
                for (ConstructorDeclaration c : t.getConstructors()) collectCalls(ctorIdOf(fqn, c, ctx), t, c, ctx);
            }
        } catch (Exception e) {
            // Never abort the walk on a single bad file.
            log.warning("Could not process file for code graph " + f.getAbsolutePath() + ": " + e.getMessage());
        }
    }

    /** Finalizes accumulated nodes/edges/calls into a {@link CodeGraph}. Idempotent; does not mutate state. */
    @Override
    public CodeGraph build() {
        Map<String, GraphNode> byId = new LinkedHashMap<>();
        for (GraphNode n : nodes) byId.putIfAbsent(n.id(), n);
        List<GraphEdge> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PendingEdge e : edges) {
            if (e.target() == null || e.target().isBlank()) continue;
            String key = e.source() + "\u0000" + e.target() + "\u0000" + e.type();
            if (seen.add(key)) {
                result.add(new GraphEdge(e.source(), e.target(), e.type(), !byId.containsKey(e.target())));
            }
        }
        for (PendingCall c : calls) {
            MethodInfo target = lookup(c.receiverType(), c.methodName(), c.arity());
            if (target == null) continue;
            String key = c.source() + "\u0000" + target.id() + "\u0000CALL";
            if (seen.add(key)) {
                result.add(new GraphEdge(c.source(), target.id(), EdgeType.CALL, false));
            }
        }
        return new CodeGraph(List.copyOf(nodes), result);
    }

    // ------------------------------------------------------------------ helpers

    private String fqnOf(TypeDeclaration<?> t) {
        return t.getFullyQualifiedName().orElse(t.getNameAsString());
    }

    /** All types of the file: top-level + recursively nested (BFS, source order). */
    private List<TypeDeclaration<?>> collectTypes(CompilationUnit cu) {
        List<TypeDeclaration<?>> result = new ArrayList<>(cu.getTypes());
        Deque<TypeDeclaration<?>> queue = new ArrayDeque<>(result);
        while (!queue.isEmpty()) {
            TypeDeclaration<?> t = queue.poll();
            for (Node member : t.getMembers()) {
                if (member instanceof TypeDeclaration<?> nested) {
                    result.add(nested);
                    queue.add(nested);
                }
            }
        }
        return result;
    }

    private String importTarget(ImportDeclaration imp) {
        String name = imp.getNameAsString();
        if (imp.isStatic()) {
            if (imp.isAsterisk()) {
                // static wildcard: target the declaring type, not the raw `pkg.*` name
                return name.endsWith(".*") ? name.substring(0, name.length() - 2) : name;
            }
            int lastDot = name.lastIndexOf('.');
            return lastDot > 0 ? name.substring(0, lastDot) : name; // static non-wildcard
        }
        if (imp.isAsterisk()) {
            return null; // non-static wildcard
        }
        return name; // normal import
    }

    private void addResolvedEdge(String source, Type type, EdgeType edgeType, Context ctx) {
        String target = JavaGraphIds.resolveTypeName(type, fqNamesMapper, ctx.pkg(), ctx.imports());
        if (target == null || target.isBlank()) {
            return;
        }
        edges.add(new PendingEdge(source, target, edgeType));
    }

    private List<String> resolveParams(List<Parameter> params, Context ctx) {
        return params.stream()
                .map(p -> JavaGraphIds.resolveTypeName(p.getType(), fqNamesMapper, ctx.pkg(), ctx.imports()))
                .toList();
    }

    private void registerMethod(String fqn, MethodDeclaration m, Context ctx) {
        List<String> paramFqns = resolveParams(m.getParameters(), ctx);
        String id = JavaGraphIds.methodId(fqn, m.getNameAsString(), paramFqns);
        nodes.add(GraphNode.method(id, ctx.relFile(), JavaGraphIds.LANGUAGE));
        String returnType = JavaGraphIds.resolveTypeName(m.getType(), fqNamesMapper, ctx.pkg(), ctx.imports());
        if (returnType != null && !"void".equals(returnType)) {
            edges.add(new PendingEdge(id, returnType, EdgeType.RETURN));
        }
        for (String paramFqn : paramFqns) {
            if (paramFqn != null) {
                edges.add(new PendingEdge(id, paramFqn, EdgeType.PARAM));
            }
        }
        methodIndex.computeIfAbsent(fqn, k -> new HashMap<>())
                .computeIfAbsent(m.getNameAsString(), k -> new ArrayList<>())
                .add(new MethodInfo(id, paramFqns, returnType));
    }

    private void registerConstructor(String fqn, ConstructorDeclaration c, Context ctx) {
        List<String> paramFqns = resolveParams(c.getParameters(), ctx);
        String id = JavaGraphIds.methodId(fqn, c.getNameAsString(), paramFqns);
        nodes.add(GraphNode.method(id, ctx.relFile(), JavaGraphIds.LANGUAGE));
        for (String paramFqn : paramFqns) {
            if (paramFqn != null) {
                edges.add(new PendingEdge(id, paramFqn, EdgeType.PARAM));
            }
        }
        methodIndex.computeIfAbsent(fqn, k -> new HashMap<>())
                .computeIfAbsent(c.getNameAsString(), k -> new ArrayList<>())
                .add(new MethodInfo(id, paramFqns, null));
    }

    private String methodIdOf(String fqn, MethodDeclaration m, Context ctx) {
        return JavaGraphIds.methodId(fqn, m.getNameAsString(), resolveParams(m.getParameters(), ctx));
    }

    private String ctorIdOf(String fqn, ConstructorDeclaration c, Context ctx) {
        return JavaGraphIds.methodId(fqn, c.getNameAsString(), resolveParams(c.getParameters(), ctx));
    }

    // ------------------------------------------------------------------ call collection (precision-first)

    private void collectCalls(String sourceId, TypeDeclaration<?> enclosingType, Node body, Context ctx) {
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            String receiver = resolveReceiverType(call, enclosingType, body, ctx);
            if (receiver == null || receiver.isBlank()) {
                continue; // unresolved receiver → NO edge (precision-first)
            }
            calls.add(new PendingCall(sourceId, receiver, call.getNameAsString(), call.getArguments().size()));
        }
    }

    private String resolveReceiverType(MethodCallExpr call, TypeDeclaration<?> enclosingType, Node body, Context ctx) {
        Expression scope = call.getScope().orElse(null);
        if (scope == null) {
            return fqnOf(enclosingType);
        }
        if (scope.isThisExpr()) {
            return fqnOf(enclosingType);
        }
        if (scope.isSuperExpr()) {
            if (enclosingType.isClassOrInterfaceDeclaration()) {
                var ext = enclosingType.asClassOrInterfaceDeclaration().getExtendedTypes();
                if (!ext.isEmpty()) {
                    return JavaGraphIds.resolveTypeName(ext.get(0), fqNamesMapper, ctx.pkg(), ctx.imports());
                }
            }
            return null;
        }
        if (scope.isNameExpr()) {
            String name = scope.asNameExpr().getNameAsString();
            Type varType = findVariableType(name, body, enclosingType);
            if (varType != null) {
                return JavaGraphIds.resolveTypeName(varType, fqNamesMapper, ctx.pkg(), ctx.imports());
            }
            // Type reference: only clearly project-resolved types (drops external receivers like System).
            String fq = fqNamesMapper.getFQName(name, ctx.pkg(), ctx.imports());
            return (fq == null || fq.isBlank() || fq.equals(name)) ? null : fq;
        }
        if (scope.isObjectCreationExpr()) {
            return JavaGraphIds.resolveTypeName(scope.asObjectCreationExpr().getType(), fqNamesMapper, ctx.pkg(), ctx.imports());
        }
        if (scope.isFieldAccessExpr()) {
            String fieldName = scope.asFieldAccessExpr().getNameAsString();
            Type fieldType = findFieldType(fieldName, enclosingType);
            if (fieldType != null) {
                return JavaGraphIds.resolveTypeName(fieldType, fqNamesMapper, ctx.pkg(), ctx.imports());
            }
            return null; // e.g. System.out.println → dropped
        }
        if (scope.isMethodCallExpr()) {
            MethodInfo inner = resolveCallInfo(scope.asMethodCallExpr(), enclosingType, body, ctx);
            if (inner != null && inner.returnType() != null && !"void".equals(inner.returnType())) {
                return inner.returnType();
            }
            return null;
        }
        return null;
    }

    private MethodInfo resolveCallInfo(MethodCallExpr call, TypeDeclaration<?> enclosingType, Node body, Context ctx) {
        String receiver = resolveReceiverType(call, enclosingType, body, ctx);
        if (receiver == null || receiver.isBlank()) {
            return null;
        }
        return lookup(receiver, call.getNameAsString(), call.getArguments().size());
    }

    private Type findVariableType(String name, Node body, TypeDeclaration<?> type) {
        for (Parameter p : body.findAll(Parameter.class)) {
            if (p.getNameAsString().equals(name)) {
                return p.getType();
            }
        }
        for (VariableDeclarator v : body.findAll(VariableDeclarator.class)) {
            if (v.getNameAsString().equals(name)) {
                return v.getType();
            }
        }
        TypeDeclaration<?> current = type;
        while (current != null) {
            for (FieldDeclaration fd : current.getFields()) {
                for (VariableDeclarator v : fd.getVariables()) {
                    if (v.getNameAsString().equals(name)) {
                        return v.getType();
                    }
                }
            }
            current = current.findAncestor(TypeDeclaration.class).orElse(null);
        }
        return null;
    }

    private Type findFieldType(String name, TypeDeclaration<?> type) {
        TypeDeclaration<?> current = type;
        while (current != null) {
            for (FieldDeclaration fd : current.getFields()) {
                for (VariableDeclarator v : fd.getVariables()) {
                    if (v.getNameAsString().equals(name)) {
                        return v.getType();
                    }
                }
            }
            current = current.findAncestor(TypeDeclaration.class).orElse(null);
        }
        return null;
    }

    private MethodInfo lookup(String receiverType, String methodName, int arity) {
        Map<String, List<MethodInfo>> byName = methodIndex.get(receiverType);
        if (byName == null && simpleNameToFqn.get(receiverType) != null) {
            byName = methodIndex.get(simpleNameToFqn.get(receiverType));
        }
        if (byName == null) {
            return null;
        }
        List<MethodInfo> candidates = byName.get(methodName);
        if (candidates == null) {
            return null;
        }
        List<MethodInfo> matching = candidates.stream()
                .filter(mi -> mi.arity() == arity)
                .toList();
        // Precision-first: do NOT guess among overloads.
        return matching.size() == 1 ? matching.get(0) : null;
    }

    private String relFile(File f) {
        Path abs = f.toPath().toAbsolutePath().normalize();
        Path rel;
        if (rootDir == null) {
            rel = abs;
        } else {
            try {
                rel = rootDir.relativize(abs);
            } catch (IllegalArgumentException e) {
                rel = abs; // different roots (e.g. different drives)
            }
        }
        return rel.toString().replace(File.separatorChar, '/');
    }
}
