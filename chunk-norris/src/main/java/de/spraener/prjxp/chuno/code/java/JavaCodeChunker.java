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
