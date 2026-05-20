package de.spraener.prjxp.chuno.code.typescript;

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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Chunker für TypeScript-Dateien.
 * <p>
 * Zerlegt eine TypeScript-Datei in semantisch sinnvolle Chunks:
 * <ul>
 *   <li><b>Import-Chunk</b> — alle {@code import}-Statements der Datei</li>
 *   <li><b>Methode-Chunks</b> — Implementierungen von Klassenmethoden oder Top-Level-Funktionen
 *       (inklusive optionaler JSDoc-Kommentare als separate Chunks)</li>
 *   <li><b>Class-Frame-Chunk</b> — Klassendeklaration mit Member-Signaturen (ohne Implementierungen),
 *       Importe und optionalem JSDoc</li>
 * </ul>
 * <p>
 * Die Chunk-Größe und Überlappung sind über die Konfigurationsparameter
 * {@code typescript.chunksize} (Default: 1300) und {@code typescript.chunkoverlap} (Default: 100) einstellbar.
 *
 * @author ChunkNorris Team
 * @see de.spraener.prjxp.common.code.typescript.TypeScriptCodeSection
 */
@Component
@RequiredArgsConstructor
@ChunkNorrisComponent
@Log
public class TypeScriptCodeChunker {

    private static final String TYPESCRIPT_CODE_MIME_TYPE = "text/x-typescript-code";

    /**
     * Metadaten-Schlüssel für die Klassifizierung von TypeScript-Code-Abschnitten.
     * <p>
     * Der zugehörige Wert ist der Name einer {@link de.spraener.prjxp.common.code.typescript.TypeScriptCodeSection},
     * z. B. {@code "imports"}, {@code "method"} oder {@code "class_frame"}.
     */
    public static final String MDKEY_CODESECTION = "typescript_code_section";

    @Value("${typescript.chunksize:1300}")
    private int chunkSize;
    @Value("${typescript.chunkoverlap:100}")
    private int overlap;

    private static final Pattern IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+.*from\\s+['\"].*['\"];?\\s*$");
    private static final Pattern EXPORT_PATTERN = Pattern.compile("^\\s*export\\s+.*");
    private static final Pattern CLASS_PATTERN = Pattern.compile("^\\s*(export\\s+)?(abstract\\s+)?(class|interface)\\s+(\\w+)");
    private static final Pattern METHOD_PATTERN = Pattern.compile("^\\s*(public|private|protected)?\\s*(async\\s+)?(\\w+)\\s*\\(.*\\)\\s*(:\\s*\\S+)?\\s*\\{");
    private static final Pattern FUNCTION_PATTERN = Pattern.compile("^\\s*(export\\s+)?(async\\s+)?function\\s+(\\w+)\\s*\\(.*\\)");

    /**
     * Zerlegt eine TypeScript-Datei in semantisch strukturierte Chunks.
     * <p>
     * Der Chunking-Prozess erzeugt drei Arten von Chunks:
     * <ol>
     *   <li><b>Import-Chunk</b> — alle Import-Statements der Datei</li>
     *   <li><b>Methode-Chunks</b> — jede Klassenmethode oder Top-Level-Funktion samt Implementierung;
     *       zugehörige JSDoc-Kommentare werden als eigene Chunks extrahiert</li>
     *   <li><b>Class-Frame-Chunk</b> — Imports, optionales JSDoc und die Klassendeklaration
     *       mit Member-Signaturen (ohne Implementierungen)</li>
     * </ol>
     *
     * @param f die zu chunkende TypeScript-Datei
     * @return ein Stream von {@link PxChunk}-Objekten; leerer Stream bei Fehlern
     */
    @Chunker(fileTypes = PxFileType.TYPESCRIPT_CODE)
    public Stream<PxChunk> chunk(File f) {
        try {
            List<String> codeLines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);

            List<PxChunk> chunks = new ArrayList<>();
            chunks.addAll(createClassFrameChunk(f, codeLines));
            chunks.addAll(createImportChunk(f, codeLines));
            chunks.addAll(createMethodChunks(f, codeLines));

            return chunks.stream();
        } catch (Exception e) {
            log.warning("Exception while chunking file " + f.getAbsolutePath() + ": " + e.getMessage());
            return Stream.of();
        }
    }

    private ChunkRange getImportsRange(List<String> codeLines) {
        final ValueContainer<Integer> vcFromLine = new ValueContainer<>(Integer.MAX_VALUE);
        final ValueContainer<Integer> vcToLine = new ValueContainer<>(0);

        for (int i = 0; i < codeLines.size(); i++) {
            String line = codeLines.get(i);
            if (IMPORT_PATTERN.matcher(line).matches()) {
                if (i < vcFromLine.getValue()) {
                    vcFromLine.setValue(i);
                }
                if (i >= vcToLine.getValue()) {
                    vcToLine.setValue(i + 1);
                }
            }
        }

        if (vcFromLine.getValue() == Integer.MAX_VALUE) {
            return ChunkRange.EMPTY;
        }
        return new ChunkRange(vcFromLine.getValue(), vcToLine.getValue(), codeLines);
    }

    private Collection<PxChunk> createImportChunk(File src, List<String> codeLines) {
        ChunkRange importRange = getImportsRange(codeLines);
        String moduleName = getModuleName(src);

        return new ContentSplitter(this.chunkSize, this.overlap).splitContent(importRange, () ->
                PxChunk.create(
                        c -> c.setMimeType(TYPESCRIPT_CODE_MIME_TYPE),
                        c -> c.setParent(moduleName),
                        c -> c.setId(c.getParent() + ".imports"),
                        c -> c.setFile(src.getAbsolutePath()),
                        c -> c.getMetadata().put(MDKEY_CODESECTION, de.spraener.prjxp.common.code.typescript.TypeScriptCodeSection.IMPORTS.getName())
                )
        );
    }

    private void readLines(List<String> codeLines, Integer from, Integer to, Consumer<String> lineConsumer) {
        for (var line : codeLines.subList(from, to)) {
            lineConsumer.accept(line);
        }
    }

    private Collection<? extends PxChunk> createMethodChunks(File f, List<String> codeLines) {
        List<PxChunk> chunks = new ArrayList<>();
        String moduleName = getModuleName(f);

        // Find classes first
        List<ClassInfo> classInfoList = findClassInfos(codeLines);
        for( var classInfo : classInfoList) {
            createClassMethodChunks(f, codeLines, chunks, classInfo, moduleName);
        }
        createTopLevelFunctionChunks(f, codeLines, chunks, moduleName);

        return chunks;
    }

    private void createClassMethodChunks(File f, List<String> codeLines, List<PxChunk> chunks,
                                         ClassInfo classInfo, String moduleName) {
        int classStartLine = classInfo.startLine;
        int classEndLine = findClassEndLine(codeLines, classStartLine);

        for (int i = classStartLine + 1; i < classEndLine; i++) {
            String line = codeLines.get(i);
            Matcher methodMatcher = METHOD_PATTERN.matcher(line);

            if (methodMatcher.matches()) {
                String methodName = extractMethodName(line);
                int methodStartLine = i;
                int methodEndLine = findMethodEndLine(codeLines, methodStartLine);
                String prefix = "\t// Method %s in class %s:\n".formatted(methodName, classInfo.name);
                String id = moduleName + "." + classInfo.name + "." + methodName;
                boolean hasDoc = false;

                // Check for JSDoc comment above method
                if (i > 0 && codeLines.get(i - 1).trim().startsWith("*")) {
                    int jsdocStart = findJsDocStart(codeLines, i - 1);
                    if (jsdocStart >= 0) {
                        StringBuilder jsdocContent = new StringBuilder();
                        jsdocContent.append(prefix);
                        hasDoc = true;
                        for (int j = jsdocStart; j < i; j++) {
                            jsdocContent.append(codeLines.get(j)).append('\n');
                        }
                        chunks.addAll(new ContentSplitter(this.chunkSize, this.overlap)
                                .splitContent(jsdocContent, jsdocStart, i, () ->
                                        PxChunk.create(
                                                c -> c.setMimeType(TYPESCRIPT_CODE_MIME_TYPE),
                                                c -> c.setParent(id),
                                                c -> c.setId(id + ".jsdoc"),
                                                c -> c.setFile(f.getAbsolutePath()),
                                                c -> c.getMetadata().put(MDKEY_CODESECTION, de.spraener.prjxp.common.code.typescript.TypeScriptCodeSection.METHOD_DOC.getName())
                                        )
                                ));
                    }
                }

                // Method implementation
                StringBuilder methodImpl = new StringBuilder();
                readLines(codeLines, methodStartLine, methodEndLine, l -> methodImpl.append(l).append('\n'));
                if (!hasDoc) {
                    methodImpl.insert(0,prefix);
                }
                chunks.addAll(new ContentSplitter(this.chunkSize, this.overlap)
                        .splitContent(
                                methodImpl.toString(),
                                methodStartLine,
                                methodEndLine,
                                () -> PxChunk.create(
                                        c -> c.setMimeType(TYPESCRIPT_CODE_MIME_TYPE),
                                        c -> c.setParent(moduleName + "." + classInfo.name),
                                        c -> c.setId(id),
                                        c -> c.setFile(f.getAbsolutePath()),
                                        c -> c.getMetadata().put(MDKEY_CODESECTION, de.spraener.prjxp.common.code.typescript.TypeScriptCodeSection.METHOD.getName())
                                )
                        ));

                i = methodEndLine;
            }
        }
    }

    private void createTopLevelFunctionChunks(File f, List<String> codeLines, List<PxChunk> chunks, String moduleName) {
        for (int i = 0; i < codeLines.size(); i++) {
            String line = codeLines.get(i);
            Matcher functionMatcher = FUNCTION_PATTERN.matcher(line);

            if (functionMatcher.matches()) {
                String functionName = extractFunctionName(line);
                int functionStartLine = i;
                int functionEndLine = findMethodEndLine(codeLines, functionStartLine);

                String id = moduleName + "." + functionName;

                // Check for JSDoc comment
                if (i > 0 && codeLines.get(i - 1).trim().startsWith("*")) {
                    int jsdocStart = findJsDocStart(codeLines, i - 1);
                    if (jsdocStart >= 0) {
                        StringBuilder jsdocContent = new StringBuilder();
                        for (int j = jsdocStart; j < i; j++) {
                            jsdocContent.append(codeLines.get(j)).append('\n');
                        }
                        chunks.addAll(new ContentSplitter(this.chunkSize, this.overlap)
                                .splitContent(jsdocContent, jsdocStart, i, () ->
                                        PxChunk.create(
                                                c -> c.setMimeType(TYPESCRIPT_CODE_MIME_TYPE),
                                                c -> c.setParent(id),
                                                c -> c.setId(id + ".jsdoc"),
                                                c -> c.setFile(f.getAbsolutePath()),
                                                c -> c.getMetadata().put(MDKEY_CODESECTION, de.spraener.prjxp.common.code.typescript.TypeScriptCodeSection.METHOD_DOC.getName())
                                        )
                                ));
                    }
                }

                // Function implementation
                StringBuilder functionImpl = new StringBuilder();
                readLines(codeLines, functionStartLine, functionEndLine, l -> functionImpl.append(l).append('\n'));
                chunks.addAll(new ContentSplitter(this.chunkSize, this.overlap)
                        .splitContent(
                                functionImpl.toString(),
                                functionStartLine,
                                functionEndLine,
                                () -> PxChunk.create(
                                        c -> c.setMimeType(TYPESCRIPT_CODE_MIME_TYPE),
                                        c -> c.setParent(moduleName),
                                        c -> c.setId(id),
                                        c -> c.setFile(f.getAbsolutePath()),
                                        c -> c.getMetadata().put(MDKEY_CODESECTION, de.spraener.prjxp.common.code.typescript.TypeScriptCodeSection.METHOD.getName())
                                )
                        ));

                i = functionEndLine;
            }
        }
    }

    private Collection<? extends PxChunk> createClassFrameChunk(File f, List<String> codeLines) {
        List<PxChunk> chunks = new ArrayList<>();
        String moduleName = getModuleName(f);

        List<ClassInfo> classInfoList = findClassInfos(codeLines);
        for (var clazz : classInfoList) {
            StringBuilder content = new StringBuilder();

            // Add imports
            content.append(getImportsRange(codeLines).toCode());
            if (clazz.startLine >= 0) {
                content.append(codeLines.get(clazz.startLine)).append("\n");
            }
            // Find and add JSDoc comment if exists
            if (clazz.startLine > 0 && codeLines.get(clazz.startLine - 1).trim().startsWith("*")) {
                int jsdocStart = findJsDocStart(codeLines, clazz.startLine - 1);
                if (jsdocStart >= 0) {
                    for (int i = jsdocStart; i < clazz.startLine; i++) {
                        content.append(codeLines.get(i)).append('\n');
                    }
                }
            }

            // Add class declaration
            int classEndLine = findClassEndLine(codeLines, clazz.startLine);

            // Add class members (properties and method signatures)
            addClassMembers(codeLines, clazz.startLine, classEndLine, content);

            content.append("}\n");

            int fromLine = clazz.startLine;

            chunks.addAll(
                    new ContentSplitter(this.chunkSize, this.overlap).splitContent(content.toString(), fromLine, classEndLine,
                            () -> PxChunk.create(
                                    c -> c.setMimeType(TYPESCRIPT_CODE_MIME_TYPE),
                                    c -> c.setId(moduleName + "." + clazz.name),
                                    c -> c.setFile(f.getAbsolutePath()),
                                    c -> c.getMetadata().put(MDKEY_CODESECTION, de.spraener.prjxp.common.code.typescript.TypeScriptCodeSection.CLASS_FRAME.getName())
                            )
                    )
            );
        }

        return chunks;
    }

    private void addClassMembers(List<String> codeLines, int classStartLine, int classEndLine, StringBuilder content) {
        content.append("\n");

        for (int i = classStartLine + 1; i < classEndLine && i < codeLines.size(); i++) {
            String line = codeLines.get(i);

            // Add property declarations
            if (!line.trim().startsWith("//") && !line.trim().startsWith("*") &&
                    !METHOD_PATTERN.matcher(line).matches() &&
                    (line.contains(":") || line.contains("=")) &&
                    !line.contains("{")) {
                content.append("    ").append(line).append('\n');
            }

            // Add method signatures
            Matcher methodMatcher = METHOD_PATTERN.matcher(line);
            if (methodMatcher.matches()) {
                if (line.endsWith("{")) {
                    line = line.replace("{", "").trim();
                    line = line + ";";
                }
                content.append("    ").append(line);
                content.append('\n');
                i = findMethodEnd(codeLines, i, classEndLine);
            }
        }
    }

    private int findMethodEnd(List<String> codeLines, int startLine, int classEndLine) {
        int braceCount = 0;
        boolean foundOpening = false;

        for (int i = startLine; i < classEndLine; i++) {
            String line = codeLines.get(i);
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    braceCount++;
                    foundOpening = true;
                } else if (c == '}') {
                    braceCount--;
                }
            }
            if (foundOpening && braceCount == 0) {
                return i + 1;
            }
        }
        return classEndLine;
    }

    private List<ClassInfo> findClassInfos(List<String> codeLines) {
        List<ClassInfo> classInfos = new ArrayList<>();
        for (int i = 0; i < codeLines.size(); i++) {
            String line = codeLines.get(i);
            Matcher matcher = CLASS_PATTERN.matcher(line);
            if (matcher.find()) {
                String className = matcher.group(4);
                classInfos.add(new ClassInfo(className, i));
            }
        }
        return classInfos;
    }

    private int findClassEndLine(List<String> codeLines, int classStartLine) {
        int braceCount = 0;
        boolean foundOpening = false;

        for (int i = classStartLine; i < codeLines.size(); i++) {
            String line = codeLines.get(i);
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    braceCount++;
                    foundOpening = true;
                } else if (c == '}') {
                    braceCount--;
                    if (foundOpening && braceCount == 0) {
                        return i + 1;
                    }
                }
            }
        }

        return codeLines.size();
    }

    private int findMethodEndLine(List<String> codeLines, int methodStartLine) {
        int braceCount = 0;
        boolean foundOpening = false;

        for (int i = methodStartLine; i < codeLines.size(); i++) {
            String line = codeLines.get(i);
            for (char c : line.toCharArray()) {
                if (c == '{') {
                    braceCount++;
                    foundOpening = true;
                } else if (c == '}') {
                    braceCount--;
                    if (foundOpening && braceCount == 0) {
                        return i + 1;
                    }
                }
            }
        }

        return codeLines.size();
    }

    private int findJsDocStart(List<String> codeLines, int commentEndLine) {
        int startLine = commentEndLine;
        while (startLine >= 0 && (codeLines.get(startLine).trim().startsWith("*") ||
                codeLines.get(startLine).trim().startsWith("/*"))) {
            startLine--;
        }
        return startLine + 1;
    }

    private String extractMethodName(String line) {
        Matcher matcher = METHOD_PATTERN.matcher(line);
        if (matcher.find()) {
            return matcher.group(3);
        }
        return "unknown";
    }

    private String extractFunctionName(String line) {
        Matcher matcher = FUNCTION_PATTERN.matcher(line);
        if (matcher.find()) {
            return matcher.group(3);
        }
        return "unknown";
    }

    private String getModuleName(File f) {
        String name = f.getName();
        if (name.endsWith(".ts")) {
            name = name.substring(0, name.length() - 3);
        }
        return name;
    }

    private static class ClassInfo {
        String name;
        int startLine;

        ClassInfo(String name, int startLine) {
            this.name = name;
            this.startLine = startLine;
        }
    }
}

