package de.spraener.gldrtrvr.javadoc;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter;
import com.github.javaparser.utils.SourceRoot;
import de.spraener.gldrtrvr.GldRtrvrCfg;
import de.spraener.gldrtrvr.GldRtrvrQuestioner;
import de.spraener.gldrtrvr.PxChunkDao;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.util.ValueContainer;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Log
public class JavaDocEnricher {

    public static final long MIN_WAIT = 5000;
    private final GldRtrvrQuestioner questioner;
    private final PxChunkDao chunkDao;
    private final GldRtrvrCfg cfg;

    public void enrichProject(Path[] paths) throws IOException {
        Arrays.asList(paths).stream()
                .parallel()
                .forEach(p -> {
                    try {
                        enrichProject(p);
                    } catch (IOException e) {
                        log.severe("Exception while enriching project " + p.toAbsolutePath().toString() + ": " + e.getMessage());
                    }
                });
    }

    public void enrichProject(Path rootPath) throws IOException {
        final ValueContainer<Long> vcLastGeminiCallTS = new ValueContainer<>(0L);

        // SourceRoot für das Verzeichnis initialisieren
        SourceRoot sourceRoot = new SourceRoot(rootPath);

        // 1. Alle Java-Dateien im Pfad parsen
        // tryWithIntermediateResults sorgt dafür, dass wir auch bei Teilfehlern weitermachen
        List<ParseResult<CompilationUnit>> parseResults = sourceRoot.tryToParse();

        for (ParseResult<CompilationUnit> result : parseResults) {
            result.getResult().ifPresent(cu -> {
                try {
                    //Originalpfad holen (z.B. .../my-project/src/main/java/de/pkg/MyClass.java)
                    Path originalPath = cu.getStorage().get().getPath();
                    //Zielpfad definieren (Basisverzeichnis im target-Ordner)
                    Path targetBase = Path.of("build/enriched-src");
                    // 3. Den relativen Pfad ab "src" extrahieren
                    // Wir suchen den Index von "src", um die Package-Struktur beizubehalten
                    String pathStr = originalPath.toString();
                    int srcIndex = pathStr.indexOf("src");
                    String relativePath = (srcIndex != -1) ? pathStr.substring(srcIndex) : originalPath.getFileName().toString();

                    Path targetPath = targetBase.resolve(relativePath);
                    if (targetPath.toFile().exists()) {
                        log.info("Class " + cu.getPrimaryType().get().getNameAsString() + " already enriched, skipping");
                        return;
                    }
                    // LexicalPreservingPrinter für jede CU einzeln aufsetzen
                    LexicalPreservingPrinter.setup(cu);
                    boolean changed = false;

                    // 2. Suche nach Methoden ohne Javadoc
                    List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
                    for (MethodDeclaration method : methods) {
                        if (!method.getJavadocComment().isPresent()) {
                            // Hier dein Aufruf an Golden Retriever & LLM
                            long callDuration = System.currentTimeMillis() - vcLastGeminiCallTS.getValue();
                            if (callDuration < MIN_WAIT) {
                                try {
                                    Thread.sleep(MIN_WAIT - callDuration);
                                } catch (InterruptedException e) {
                                }
                            }
                            vcLastGeminiCallTS.setValue(System.currentTimeMillis());
                            String javadoc = callGldRtrvrAndLlm(cu, method);
                            if (isJavaDoc(javadoc)) {
                                // In JavaParser 3.25.10 wird der String automatisch als JavadocComment geparst
                                method.setJavadocComment(sanatize(javadoc));
                                changed = true;
                            } else {
                                log.warning("Für die Methode %s.%s wurde kein JavaDoc generiert: %s".formatted(cu.getPrimaryType().get().getFullyQualifiedName().get(), method.getDeclarationAsString(), javadoc));
                            }
                        }
                    }

                    // 3. Nur wenn Änderungen vorliegen, schreiben wir zurück
                    if (changed) {
                        String formattedCode = LexicalPreservingPrinter.print(cu);
                        try {
                            // Verzeichnisse im Ziel erstellen, falls sie nicht existieren
                            Files.createDirectories(targetPath.getParent());

                            // In den neuen Pfad schreiben
                            Files.writeString(targetPath, formattedCode, StandardCharsets.UTF_8);
                            log.info("Enriched file saved to: " + targetPath);

                        } catch (IOException e) {
                            log.severe("Fehler beim Schreiben in target/enriched: " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.severe("Fehler beim enrichment von " + cu.getPrimaryType().get().getFullyQualifiedName().get() + ": " + e.getMessage());
                }

            });
        }
    }

    private String sanatize(String javadoc) {
        try {
            BufferedReader br = new BufferedReader(new StringReader(javadoc));
            String line;
            StringBuilder sb = new StringBuilder();
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.equals("/**")) {
                    sb.append("\n");
                    continue;
                }
                if (line.equals("*/")) {
                    continue;
                }
                sb.append("     ").append(line.trim()).append("\n");
            }
            sb.append("     *\n");
            sb.append("     * Hinweis: Diese Doku wurde generiert mit chunk_norris, golden_retriever und dem ChatModel " + cfg.getChatModelName() + "\n");
            sb.append("     *\n");
            return sb.toString();
        } catch (IOException xc) {
            return javadoc;
        }
    }

    private boolean isJavaDoc(String javadoc) {
        return StringUtils.hasText(javadoc) && javadoc.startsWith("/**") && javadoc.endsWith("*/");
    }

    private String callGldRtrvrAndLlm(CompilationUnit cu, MethodDeclaration method) {
        String methodName = asMethodSignature(method);
        String methodNameInCode = toSimpleMethodName(method.getDeclarationAsString(false, false, true));
        TypeDeclaration type = (TypeDeclaration) method.getParentNode().get();
        if (type == null) {
            throw new IllegalArgumentException("Methodenklasse konnte nicht gefunden werden");
        }
        String className = type.getFullyQualifiedName().get().toString();
        if (className == null) {
            throw new IllegalArgumentException("Methodenklasse konnte nicht gefunden werden");
        }
        List<PxChunk> methodChunks = chunkDao.findById(className + "." + methodName);
        log.info("Asking for %s.%s".formatted(className, methodName));
        String question = ("Du bist eine erfahrener Java-Entwickler und sollst mich bei der Dokumentation meines QuellCodes unterstützen." +
                " Ich brauche JavaDoc für die Methode '%s' in der JavaClasse %s." +
                " Antworte nur mit dem JavaDoc-Fragment auf DEUTSCH, sodass ich die Antwort direkt verwenden kann. Füge auch keine MarkDown Tags ein.").formatted(methodName, className);
        String answer = questioner.ask(question, methodChunks,
                context -> context.contains(className) || context.contains(methodNameInCode)
        );
        if (answer.contains("/**") && answer.contains("*/")) {
            answer = answer.substring(answer.indexOf("/**"), answer.lastIndexOf("*/") + 2);
        }
        return answer;
    }

    private String toSimpleMethodName(String methodNameWithParamsAndModifiers) {
        String simpleName = methodNameWithParamsAndModifiers.substring(0, methodNameWithParamsAndModifiers.indexOf("(") + 1);
        simpleName = simpleName.substring(simpleName.lastIndexOf(' '));
        return simpleName;
    }

    private String asMethodSignature(MethodDeclaration method) {
        String methodStr = method.getDeclarationAsString(false, false, false);
        if (methodStr.contains("{")) {
            return methodStr.substring(0, methodStr.indexOf("{") - 1);
        } else {
            return methodStr;
        }
    }
}
