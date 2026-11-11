package de.spraener.prjxp.chuno.code.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Zentrale JavaParser-Konfiguration fuer alle Java-Parser-Komponenten.
 *
 * <p>Statt des globalen {@code StaticJavaParser} — ein Prozess-Singleton mit mutabler
 * Konfiguration, das jede Komponente daher defensiv vor jedem Parse neu setzen musste —
 * teilen sich alle Komponenten dieselbe Parser-Instanz mit fester Konfiguration.
 * {@link JavaParser} ist thread-safe und zwischen Parse-Aufrufen stateless,
 * eine geteilte Instanz ist also sicher.
 *
 * <p>Die {@link #parse} -Methoden haben exakt die Semantik von
 * {@code StaticJavaParser.parse}: sie liefern das {@link CompilationUnit} bei Erfolg
 * und werfen bei Parse-Fehlern eine {@link ParseProblemException}.
 */
@Component
public class JavaParserFactory {

    private final JavaParser parser = new JavaParser(
            new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_24));

    /**
     * Parst die Datei mit der zentralen Konfiguration.
     *
     * @throws ParseProblemException wenn die Datei nicht geparsed werden kann
     * @throws FileNotFoundException wenn die Datei nicht existiert
     */
    public CompilationUnit parse(File file) throws FileNotFoundException {
        ParseResult<CompilationUnit> result = parser.parse(file);
        if (result.isSuccessful()) {
            return result.getResult().get();
        }
        throw new ParseProblemException(result.getProblems());
    }

    /**
     * Parst den Code-String mit der zentralen Konfiguration.
     *
     * @throws ParseProblemException wenn der Code nicht geparsed werden kann
     */
    public CompilationUnit parse(String code) {
        ParseResult<CompilationUnit> result = parser.parse(code);
        if (result.isSuccessful()) {
            return result.getResult().get();
        }
        throw new ParseProblemException(result.getProblems());
    }
}
