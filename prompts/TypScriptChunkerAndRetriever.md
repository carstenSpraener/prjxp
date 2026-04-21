# TypeScript RAG with Chunk Norris and Golden Retriever

For my prjxp project I want to add support for TypeScript to deal with 
Angular projects. The prjxp already has support for java code. This java 
support includes chunking of java classes into logical units like methods 
and class frames. The chunks are stored with meta-data to make later 
retrieval possible.

## The Code chunker

The TypeScript support should do the same analog to the java support so that:

* The chunks are stored with meta-data to make later retrieval possible.
* The chunks are organized in a way that allows efficient retrieval based on 
  semantic similarity. See the meta-data attributes in the example below.
* A chunk should always fit into the 512-tokens limit of the embedding model.
* Each Sub-Chunk has a parent referred to by the attribute "parent"
* A Class Chunk is the root of a chunk tree and has no parent.
* Use the following Code of the JavaCode Chunker as a blueprint for the type 
  script chunker:

```java
package de.spraener.prjxp.chuno.code.java;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import de.spraener.prjxp.common.annotations.ChunkNorrisComponent;
import de.spraener.prjxp.common.annotations.Chunker;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxFileType;
import de.spraener.prjxp.common.util.ChunkRange;
import de.spraener.prjxp.common.util.ContentSplitter;
import de.spraener.prjxp.common.util.ValueContainer;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
@ChunkNorrisComponent
@Log
public class JavaCodeChunker {
    private static final String CHUNKER_NAME = "JavaCodeChunker";
    private static final String JAVA_CODE_MIME_TYPE = "text/x-java-code";
    public static final String MDKEY_CODESECTION = "java_code_section";

    private final JavaDependencyHandler javaDependencyHandler;

    @Value("${java.chunksize:1300}")
    private int chunkSize;
    @Value("${java.chunkoverlap:100}")
    private int overlap;

    @Chunker(fileTypes = PxFileType.JAVA_CODE)
    public Stream<PxChunk> chunk(File f) {
        try {
            List<String> codeLines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            StaticJavaParser.getConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
            CompilationUnit cu = StaticJavaParser.parse(f);

            javaDependencyHandler.fillDependencies(cu);

            List<PxChunk> chunks = new ArrayList<>();
            chunks.addAll(createImportChunk(f, cu, codeLines));
            chunks.addAll(createMethodChunks(f, cu, codeLines));
            chunks.addAll(createClassFrameChunk(f, cu, codeLines));
            chunks.addAll(createMetaChunk(cu, chunks, codeLines));
            return chunks.stream();
        } catch (Exception e) {
            log.warning("Exception while chunking file " + f.getAbsolutePath() + ": " + e.getMessage());
            return Stream.of();
        }
    }

    private ChunkRange getImportsRange(CompilationUnit cu, List<String> codeLines) {
        final ValueContainer<Integer> vcFromLine = new ValueContainer(Integer.MAX_VALUE);
        final ValueContainer<Integer> vcToLine = new ValueContainer(0);
        for (var imp : cu.getImports()) {
            imp.getRange().ifPresent(r -> {
                        if (r.begin.line < vcFromLine.getValue()) {
                            vcFromLine.setValue(r.begin.line);
                        }
                        if (r.end.line > vcToLine.getValue()) {
                            vcToLine.setValue(r.end.line);
                        }
                    }
            );
        }
        if (vcFromLine.getValue() == Integer.MAX_VALUE) {
            return ChunkRange.EMPTY;
        }
        int fromLine = vcFromLine.getValue() - 1;
        int toLine = vcToLine.getValue();
        return new ChunkRange(fromLine, toLine, codeLines);
    }

    private Collection<PxChunk> createImportChunk(File src, CompilationUnit cu, List<String> codeLines) throws IOException {
        ChunkRange importRange = getImportsRange(cu, codeLines);
        return new ContentSplitter(this.chunkSize, this.overlap).splitContent(importRange, () ->
                PxChunk.create(
                        c -> c.setMimeType(JAVA_CODE_MIME_TYPE),
                        c -> c.setParent(cu.getPrimaryType().get().getFullyQualifiedName().get()),
                        c -> c.setId(c.getParent() + ".imports"),
                        c -> c.setFile(src.getAbsolutePath()),
                        c -> c.getMetadata().put(MDKEY_CODESECTION, de.spraener.prjxp.common.code.java.JavaCodeSection.IMPORTS.getName())
                )
        );
    }

    private void readLines(List<String> codeLines, Integer from, Integer to, Consumer<String> lineConsumer) {
        for (var line : codeLines.subList(from, to)) {
            lineConsumer.accept(line);
        }
    }

    private Collection<? extends PxChunk> createMethodChunks(File f, CompilationUnit cu, List<String> codeLines) {
        List<PxChunk> chunks = new ArrayList<>();
        if (cu.getPrimaryType().isPresent()) {
            var primaryType = cu.getPrimaryType().get();
            createContainedMethodChunks(f, cu, chunks, primaryType, codeLines);
        }
        for (var subClazz : cu.getTypes()) {
            if (!subClazz.equals(cu.getPrimaryType().get())) {
                createContainedMethodChunks(f, cu, chunks, subClazz, codeLines);
            }
        }
        return chunks;
    }

    private void createContainedMethodChunks(File f, CompilationUnit cu, List<PxChunk> chunks, TypeDeclaration<?> type, List<String> codeLines) {
        for (var m : type.getMethods()) {
            String clazzName = type.getFullyQualifiedName().get().toString();
            String methodSig = m.getDeclarationAsString(false, false, false);
            String id = clazzName + "." + methodSig;
            m.getJavadocComment().ifPresent(jc -> {
                StringBuilder content = new StringBuilder();
                int fromLine = jc.getRange().get().begin.line - 1;
                int toLine = jc.getRange().get().end.line;
                readLines(codeLines, fromLine, toLine, l -> content.append(l).append('\n'));
                chunks.addAll(new ContentSplitter(this.chunkSize, this.overlap)
                        .withContentPrefix("//Methode %s in class %s:".formatted(methodSig, clazzName))
                        .splitContent(content, fromLine, toLine, () ->
                                PxChunk.create(
                                        c -> c.setMimeType(JAVA_CODE_MIME_TYPE),
                                        c -> c.setParent(id),
                                        c -> c.setId(id + ".javadoc"),
                                        c -> c.setFile(f.getAbsolutePath()),
                                        c -> c.getMetadata().put(MDKEY_CODESECTION, de.spraener.prjxp.common.code.java.JavaCodeSection.METHOD_DOC.getName())
                                )
                        ));
            });
            StringBuilder methodImpl = new StringBuilder();
            addAnnotationsIfExist(methodImpl, m, "");
            int fromLine = m.getRange().get().begin.line - 1;
            int toLine = m.getRange().get().end.line;
            readLines(codeLines, fromLine, toLine, l -> methodImpl.append(l).append('\n'));
            chunks.addAll(new ContentSplitter(this.chunkSize, this.overlap)
                    .withContentPrefix("//Methode %s in class %s:".formatted(methodSig, clazzName))
                    .splitContent(
                            methodImpl.toString(),
                            fromLine,
                            toLine,
                            () -> PxChunk.create(
                                    c -> c.setMimeType(JAVA_CODE_MIME_TYPE),
                                    c -> c.setParent(type.getFullyQualifiedName().get().toString()),
                                    c -> c.setId(id),
                                    c -> c.setFile(f.getAbsolutePath()),
                                    c -> c.getMetadata().put(MDKEY_CODESECTION, de.spraener.prjxp.common.code.java.JavaCodeSection.METHOD.getName())
                            )
                    ));
        }
    }

    private Collection<? extends PxChunk> createClassFrameChunk(File f, CompilationUnit cu, List<String> codeLines) {
        List<PxChunk> chunks = new ArrayList<>();
        for (var clazz : cu.getTypes()) {
            StringBuilder content = new StringBuilder();
            cu.getPackageDeclaration().ifPresent(pd -> content.append(pd.toString()).append('\n'));
            content.append(getImportsRange(cu, codeLines).toCode());
            clazz.getJavadocComment().ifPresent(jc -> content.append(jc.toString()).append('\n'));
            for (int idx = clazz.getRange().get().begin.line - 1; idx == 0 || !codeLines.get(idx - 1).contains("{"); idx++) {
                content.append(codeLines.get(idx)).append('\n');
            }
            for (var attr : clazz.getFields()) {
                content.append("    ").append(attr.toString()).append('\n');
            }
            content.append("\n");
            for (var m : clazz.getMethods()) {
                addAnnotationsIfExist(content, m, "    ");
                content.append("    ").append(m.getDeclarationAsString(false, false, false)).append('\n');
            }
            content.append("}\n");
            int fromLine = clazz.getRange().get().begin.line - 1;
            int toLine = clazz.getRange().get().end.line;
            chunks.addAll(
                    new ContentSplitter(this.chunkSize, this.overlap).splitContent(content.toString(), fromLine, toLine,
                            () -> {
                                return PxChunk.create(
                                        c -> c.setMimeType(JAVA_CODE_MIME_TYPE),
                                        c -> c.setId(clazz.getFullyQualifiedName().get().toString()),
                                        c -> c.setFile(f.getAbsolutePath()),
                                        c -> c.getMetadata().put(MDKEY_CODESECTION, de.spraener.prjxp.common.code.java.JavaCodeSection.CLAZZ_FRAME.getName())
                                );
                            }

                    )
            );
        }
        return chunks;
    }

    private void addAnnotationsIfExist(StringBuilder content, MethodDeclaration m, String identation) {
        String annotations = m.getAnnotations().stream()
                .map(AnnotationExpr::toString)
                .collect(Collectors.joining("\n" + identation));
        if (StringUtils.hasText(annotations)) {
            content.append(identation).append(annotations).append('\n');
        }
    }

    private Collection<? extends PxChunk> createMetaChunk(CompilationUnit cu, List<PxChunk> chunks, List<String> codeLines) {
        return List.of();
    }

}
```

The underlying PxChunk-Class is here:

```java
package de.spraener.prjxp.common.model;

import de.spraener.prjxp.common.util.ContentSplitter;
import lombok.Data;
import lombok.extern.java.Log;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Data
@Log
public class PxChunk {
    public static final String PXCHUNK_ID = "pxchunk_id";
    public static final String PXCHUNK_MIME_TYPE = "pxchunk_mimeType";
    public static final String PXCHUNK_FILE = "pxchunk_file";
    public static final String PXCHUNK_PARENT = "pxchunk_parent";
    public static final String PXCHUNK_PART = "pxchunk_part";
    public static final String PXCHUNK_TOTAL = "pxchunk_total";
    public static final String PXCHUNK_FROM_LINE = "pxchunk_fromLine";
    public static final String PXCHUNK_TO_LINE = "pxchunk_toLine";
    public static final String PXCHUNK_SIZE = "pxchunk_size";
    public static final String PXCHUNK_OVERLAP = "pxchunk_overlap";
    public static final String PXCHUNK_METADATA = "pxchunk_metadata";

    private String id;
    private String mimeType;
    private String file;
    private String parent;
    private int part;
    private int total;
    private String fromLine;
    private String toLine;
    private int size;
    private int overlap;
    private Map<String, String> metadata = new HashMap<>();

    private String content;

    private PxChunk() {
    }

    public static Map<String, String> metadataAsMap(PxChunk chunk) {
        Map<String, String> map = new HashMap<>();
        ifNotNull(chunk.id, () -> map.put(PXCHUNK_ID, chunk.id));
        ifNotNull(chunk.mimeType, () -> map.put(PXCHUNK_MIME_TYPE, chunk.mimeType));
        ifNotNull(chunk.file, () -> map.put(PXCHUNK_FILE, chunk.file));
        ifNotNull(chunk.parent, () -> map.put(PXCHUNK_PARENT, chunk.parent));
        ifNotNull(chunk.part, () -> map.put(PXCHUNK_PART, "" + chunk.part));
        ifNotNull(chunk.total, () -> map.put(PXCHUNK_TOTAL, "" + chunk.total));
        ifNotNull(chunk.fromLine, () -> map.put(PXCHUNK_FROM_LINE, chunk.fromLine));
        ifNotNull(chunk.toLine, () -> map.put(PXCHUNK_TO_LINE, chunk.toLine));
        ifNotNull(chunk.size, () -> map.put(PXCHUNK_SIZE, "" + chunk.size));
        ifNotNull(chunk.overlap, () -> map.put(PXCHUNK_OVERLAP, "" + chunk.overlap));
        for (var e : chunk.getMetadata().entrySet()) {
            map.put(PXCHUNK_METADATA + "." + e.getKey(), e.getValue());
        }
        return map;
    }

    private static void ifNotNull(Object value, Runnable r) {
        if (value != null) {
            r.run();
        }
    }

    public static PxChunk fromContentAndMap(String content, Map<String, Object> objMetadata) {
        Map<String, String> metadata = new HashMap<>();
        objMetadata.forEach((key, value) -> {
            if (value != null) {
                metadata.put(key, value.toString());
            }
        });
        PxChunk chunk = new PxChunk();
        chunk.setContent(content);

        // 1. System-Felder zurückmappen
        // ifPresent-Logik oder einfache Zuweisung über deine Konstanten
        chunk.setId((metadata.get(PXCHUNK_ID).toString()));
        chunk.setMimeType(metadata.get(PXCHUNK_MIME_TYPE));
        chunk.setFile(metadata.get(PXCHUNK_FILE));
        chunk.setParent(metadata.get(PXCHUNK_PARENT));

        // Numerische Felder mit sicherem Parsing
        if (metadata.containsKey(PXCHUNK_PART)) chunk.setPart(Integer.parseInt(metadata.get(PXCHUNK_PART)));
        if (metadata.containsKey(PXCHUNK_TOTAL)) chunk.setTotal(Integer.parseInt(metadata.get(PXCHUNK_TOTAL)));
        if (metadata.containsKey(PXCHUNK_SIZE)) chunk.setSize(Integer.parseInt(metadata.get(PXCHUNK_SIZE)));
        if (metadata.containsKey(PXCHUNK_OVERLAP)) chunk.setOverlap(Integer.parseInt(metadata.get(PXCHUNK_OVERLAP)));

        chunk.setFromLine(metadata.get(PXCHUNK_FROM_LINE));
        chunk.setToLine(metadata.get(PXCHUNK_TO_LINE));

        // 2. Dynamische Metadaten extrahieren
        // Wir suchen nach Keys, die mit dem Präfix "PXCHUNK_METADATA." starten
        String prefix = PXCHUNK_METADATA + ".";
        metadata.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                String realKey = key.substring(prefix.length());
                chunk.getMetadata().put(realKey, value);
            }
        });

        return chunk;
    }

    public static PxChunk create(Consumer<PxChunk>... modifier) {
        PxChunk chunk = new PxChunk();
        if (modifier != null) {
            for (Consumer<PxChunk> m : modifier) {
                m.accept(chunk);
            }
        }
        return chunk;
    }

    public static PxChunk createPxChunk(String mimeType,
                                        int fromLine,
                                        int toLine,
                                        int overlap,
                                        String parentID,
                                        String chunkID,
                                        File f,
                                        String content,
                                        Consumer<Map<String, Object>>... metaDataModifier
    ) {
        Map<String, Object> metaData = new HashMap<>();
        metaData.put(PxChunk.PXCHUNK_FILE, f.getAbsolutePath());
        metaData.put(PxChunk.PXCHUNK_ID, chunkID);
        metaData.put(PxChunk.PXCHUNK_PARENT, parentID);
        metaData.put(PxChunk.PXCHUNK_SIZE, String.valueOf(content.length()));
        metaData.put(PxChunk.PXCHUNK_MIME_TYPE, mimeType);
        metaData.put(PxChunk.PXCHUNK_FROM_LINE, "" + fromLine);
        metaData.put(PxChunk.PXCHUNK_TO_LINE, "" + toLine);
        metaData.put(PxChunk.PXCHUNK_OVERLAP, "" + overlap);
        if (metaDataModifier != null) {
            for (Consumer<Map<String, Object>> modifier : metaDataModifier) {
                modifier.accept(metaData);
            }
        }
        return PxChunk.fromContentAndMap(content, metaData);
    }

    public static PxChunk combine(List<PxChunk> chunkList) {
        if (chunkList == null || chunkList.isEmpty()) {
            return null;
        }
        Collections.sort(chunkList, (c1, c2) -> c1.part - c2.part);
        PxChunk combined = new PxChunk();
        PxChunk root = chunkList.get(0);
        combined.id = root.id;
        combined.mimeType = root.mimeType;
        combined.file = root.file;
        combined.parent = root.parent;
        combined.fromLine = root.fromLine;
        combined.toLine = root.toLine;
        combined.size = root.size;
        combined.overlap = root.overlap;
        combined.metadata = new HashMap<>(root.metadata);
        String content = new ContentSplitter(root.size, root.overlap)
                .unsplit(chunkList);
        combined.content = content;
        return combined;
    }
}
```

## Retrieval

The retriever is responsible for building an efficient and focused prompt for 
the LLM. This is done by finding relevant chunks with a vector search in the 
chromaDB and combining the chunks so that a focused and understandable prompt 
can be built. This is where the chunker and the retriever work together on 
the meta-data. 

For the Java support there is already an implementation of the JavaRetriever.
Here is the code of this JavaRetriever: 

```java
package de.spraener.prjxp.gldrtrvr.code.java;

import de.spraener.prjxp.common.code.java.JavaCodeSection;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.gldrtrvr.PxChunkDao;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Service
@RequiredArgsConstructor
@Log
public class JavaRetriever {
    private final PxChunkDao chunkDao;

    @SafeVarargs
    public final StringBuilder buildPromptForFindings(StringBuilder prompt, List<PxChunk> chunks, Function<String, Boolean>... contextValidators) {
        JavaPromptSession session = new JavaPromptSession(chunkDao);
        session.setChunks(combineChunksByID(chunks));
        prompt.append(session.buildPrompt(this::modifyPromptByChunk, contextValidators));
        return prompt;
    }

    private String modifyPromptByChunk(PxChunk pxChunk, String prompt) {
        String nextPrompt = prompt;
        if (pxChunk.getMetadata().containsKey("java_code_section")) {
            JavaCodeSection section = JavaCodeSection.fromName(pxChunk.getMetadata().get("java_code_section"));
            switch (section) {
                case METHOD:
                    PxChunk javaDoc = PxChunk.combine(chunkDao.findById(pxChunk.getId() + ".javadoc"));
                    if (javaDoc != null) {
                        nextPrompt = insertBefore(prompt, toMethodName(pxChunk), javaDoc.getContent());
                        prompt = nextPrompt;
                    }
                    nextPrompt = replaceInPrompt(prompt, toMethodName(pxChunk), pxChunk.getContent());
                    break;
                case DEPENDENCIE_INFO:
                    nextPrompt = prompt + pxChunk.getContent();
                    break;
                case METHOD_DOC:
                    nextPrompt = insertBefore(prompt, toMethodName(pxChunk), pxChunk.getContent());
                    break;
                case CLAZZ_FRAME:
                    String className = pxChunk.getId();
                    nextPrompt = prompt + "\n\n## Hier ein Rumpf der Klasse " + className + ":\n\n```java\n" + pxChunk.getContent() + "\n```\n";
                    PxChunk dependenyChunk = PxChunk.combine(chunkDao.findById(pxChunk.getId() + ".dependencies"));
                    if (dependenyChunk != null) {
                        nextPrompt += "\n\n### Hier noch Infos zu den Dependencies innerhalb des Projekts:\n\n" + dependenyChunk.getContent();
                    }
                    break;
                default:
                    break;
            }
        }
        return nextPrompt;
    }

    private String insertBefore(String prompt, String methodName, String content) {
        int splittIdx = prompt.indexOf(methodName);
        if (splittIdx < 0) {
            log.warning("Methodenname %s nicht gefunden in Prompt: %s".formatted(methodName, prompt));
        }
        String prefix = prompt.substring(0, splittIdx);
        String postFix = prompt.substring(splittIdx);
        return prefix + content + postFix;
    }

    private String replaceInPrompt(String prompt, String methodName, String content) {
        return prompt.replace(methodName, content);
    }

    private String toMethodName(PxChunk c) {
        return c.getId().substring(c.getId().lastIndexOf('.') + 1);
    }

    private List<PxChunk> combineChunksByID(List<PxChunk> chunks) {
        Map<String, List<PxChunk>> chunkMap = new HashMap<>();
        for (var c : chunks) {
            List<PxChunk> idList = chunkMap.computeIfAbsent(c.getId(), k -> new ArrayList<>());
            idList.add(c);
        }
        List<PxChunk> result = new ArrayList<>();
        for (var chunkList : chunkMap.values()) {
            PxChunk c = chunkList.getFirst();
            if (c.getTotal() > chunkList.size()) {
                result.add(combineChunks(chunkDao.findById(c.getId())));
            } else {
                result.add(combineChunks(chunkList));
            }
        }
        return result;
    }

    private PxChunk combineChunks(List<PxChunk> chunkList) {
        return PxChunk.combine(chunkList);
    }
}

```

It uses a JavaPromptSession to build a prompt. Here is the code of that class:

```java
package de.spraener.prjxp.gldrtrvr.code.java;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.util.ValueContainer;
import de.spraener.prjxp.gldrtrvr.PxChunkDao;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkNode;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

@Data
public class JavaPromptSession {
    private Map<String, PxChunk> chunkStore = new HashMap<>();
    private PxChunkDao chunkDao;
    private List<PxChunk> chunks;
    private List<ChunkNode> rootForrest = new ArrayList<>();
    private final int maxContentLength = 50000;

    public JavaPromptSession(PxChunkDao chunkDao) {
        this.chunkDao = chunkDao;
    }

    record RankedPrompt(int rootRank, String treeContext) {
    }

    public void setChunks(List<PxChunk> chunks) {
        this.chunks = chunks;
        this.rootForrest.clear();
        for (var chunk : chunks) {
            ChunkNode root = findRootForChunk(chunk);
            if (root == null) {
                root = buildGraphToRoot(chunk).root();
                rootForrest.add(root);
            }
            root.rank(chunk);
        }
    }

    public String buildPrompt(BiFunction<PxChunk, String, String> promptModifier, Function<String, Boolean>... contextValidator) {
        String context = "";
        List<RankedPrompt> rankedPrompts = new ArrayList<>();
        for (var r : this.rootForrest) {
            final ValueContainer<String> vcPrompt = new ValueContainer<String>("");
            r.visit(c -> {
                vcPrompt.setValue(promptModifier.apply(c.getChunk(), vcPrompt.getValue()));
            });
            String treeContext = vcPrompt.getValue();
            if (contextValidator != null) {
                boolean valid = true;
                for (var v : contextValidator) {
                    valid &= v.apply(treeContext);
                }
                if (!valid) {
                    continue;
                }
            }
            rankedPrompts.add(new RankedPrompt(r.getRootRank(), treeContext));
        }
        rankedPrompts.sort((r1, r2) -> r2.rootRank() - r1.rootRank());
        for (var rp : rankedPrompts) {
            if (rp.rootRank() == 0) {
                break;
            }
            context += rp.treeContext();
            if (context.length() > maxContentLength) {
                break;
            }
        }
        return context;
    }

    private ChunkNode findRootForChunk(PxChunk chunk) {
        for (var r : rootForrest) {
            if (findChunkNodeInTree(r, chunk) != null) {
                return r;
            }
        }
        if (chunk.getParent() != null) {
            PxChunk parent = readChunk(chunk.getParent());
            if (parent == null) {
                return null;
            }
            ChunkNode root = findRootForChunk(parent);
            if (root != null) {
                findChunkNodeInTree(root, parent).addChild(new ChunkNode(chunk.getId(), chunk.getMetadata().get("java_code_section"), this::readChunk));
            }
            return root;
        }
        return null;
    }

    private ChunkNode findChunkNodeInTree(ChunkNode r, PxChunk chunk) {
        if (r.getChunkID().equals(chunk.getId())) {
            return r;
        }
        for (var child : r.getChilds()) {
            ChunkNode found = findChunkNodeInTree(child, chunk);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public PxChunk readChunk(String id) {
        return chunkStore.computeIfAbsent(id, k -> loadAndCombine(k));
    }

    private PxChunk loadAndCombine(String id) {
        return PxChunk.combine(chunkDao.findById(id));
    }

    private ChunkNode buildGraphToRoot(PxChunk c) {
        if (!StringUtils.hasText(c.getParent())) {
            return new ChunkNode(c.getId(), c.getMetadata().get("java_code_section"), this::readChunk);
        } else {
            List<PxChunk> parentChunk = chunkDao.findById(c.getParent());
            if (parentChunk == null || parentChunk.isEmpty()) {
                c.setParent(null);
                return buildGraphToRoot(c);
            }
            ChunkNode parent = buildGraphToRoot(PxChunk.combine(parentChunk));
            ChunkNode child = new ChunkNode(c.getId(), c.getMetadata().get("java_code_section"), this::readChunk);
            parent.addChild(child);
            return child;
        }
    }

    public Stream<PxChunk> getRootChunks() {
        return rootForrest.stream().map(ChunkNode::getChunk);
    }
}
```

**Notice**: The JavaPromptSession combines multiple hits on the same java 
class to one tree. This results in a forrest of trees where each tree 
represents the code sections of one class found by the vector search.

Each tree is ranked by a heuristic. Here is the relvant Code in the ChunkNode:

```java
package de.spraener.prjxp.gldrtrvr.chunks;

import de.spraener.prjxp.common.code.java.JavaCodeSection;
import de.spraener.prjxp.common.model.PxChunk;
import lombok.Data;
import lombok.ToString;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

@Data
public class ChunkNode {
    private Function<String, PxChunk> chunkReader;
    @ToString.Exclude
    private ChunkNode parent;
    private String type;
    private String chunkID;
    private int rootRank = 0; // only maintained for root nodes (parent == null)
    private Set<String> childIDs = new HashSet<>();
    private List<ChunkNode> childs = new ArrayList<>();

    public ChunkNode(String chunkId, String type, Function<String, PxChunk> chunkReader) {
        this.type = type;
        this.chunkID = chunkId;
        this.chunkReader = chunkReader;
    }

    public PxChunk getChunk() {
        return this.chunkReader.apply(chunkID);
    }

    public void addChild(ChunkNode child) {
        this.childs.add(child);
        if (child.parent != null && child.parent != this) {
            child.parent.childs.remove(child);
        }
        child.parent = this;
    }

    public ChunkNode root() {
        ChunkNode root = this;
        while (root.parent != null) {
            root = root.parent;
        }
        return root;
    }

    public void visit(Consumer<ChunkNode> visitor) {
        visitor.accept(this);
        Collections.sort(childs, (c1, c2)->c1.chunkID.compareTo(c2.chunkID));
        for (var child : childs) {
            child.visit(visitor);
        }
    }

    public ChunkNode rank(PxChunk hitChunk) {
        if (this.parent == null) {
            this.rootRank += weightHit(hitChunk);
        }
        return this;
    }

    private static int weightHit(PxChunk hitChunk) {
        if (hitChunk.getMetadata().containsKey("java_code_section")) {
            JavaCodeSection section = JavaCodeSection.fromName(hitChunk.getMetadata().get("java_code_section"));
            switch (section) {
                case CLAZZ_FRAME:
                    return 2;
                case METHOD:
                case METHOD_DOC:
                    return 5;
                case DEPENDENCIE_INFO:
                    return 0;
                case IMPORTS:
                    return 1;
            }
        }
        if( hitChunk.getMetadata().containsKey("section_number")) {
            return hitChunk.getContent().length();
        }
        return 0;
    }
}
```

## Task

Based on this information implement a TypeScriptCodeChunker, a 
TypeScriptRetriever and a TypeScriptPromptSession to enable creation of 
TypeScript-Prompts. Use the analog package and project structure as you can 
see in the package declaration of each code example.