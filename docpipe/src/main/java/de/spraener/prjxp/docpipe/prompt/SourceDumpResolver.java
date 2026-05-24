package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;
import lombok.extern.java.Log;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.stream.Stream;

@Component
@Log
/**
 * A template resolver that dumps the source code of files from a directory into the prompt.
 * <p>
 * This resolver is used to provide the LLM with actual source code context. It can scan a 
 * single directory or recursively include subdirectories, filtering files by their extension.
 * </p>
 */
public class SourceDumpResolver implements TemplateResolver {
    @Override
    /**
     * Returns the identifier for this resolver.
     * 
     * @return "java-src-dump"
     */
    public String getID() {
        return "java-src-dump";
    }

    @Override
    /**
     * Resolves the source code dump for a given path.
     * <p>
     * This method reads files from the specified directory (and optionally subdirectories) 
     * that match the given extension, wrapping each file's content in Markdown code blocks.
     * </p>
     *
     * @param baseDir the configuration directory used as a base for resolution
     * @param context the current context of the template execution
     * @param options Handlebars options, expecting a path as the first parameter and optional 
     *                hashes {@code scanSubs} (boolean) and {@code ending} (string, default "java")
     * @return a string containing the dumped source code of all matching files
     * @throws Exception if an error occurs during file system traversal
     */
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        final Path srcPath = baseDir.toPath().resolve(options.param(0).toString());
        final boolean scanSubs = options.hash("scanSubs", false);
        final String ending = options.hash("ending", "java");
        StringBuilder sb = new StringBuilder("\n");
        try (Stream<Path> walk = scanSubs ? Files.walk(srcPath) : Files.walk(srcPath, 1)) {
            walk.filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(ending))
                .filter(path -> !path.toString().endsWith("package-info.java"))
                .forEach(path -> {
                    try(FileInputStream fis = new FileInputStream(path.toFile())) {
                        String content = IOUtils.toString(fis, StandardCharsets.UTF_8);
                        sb.append("```"+ending+"\n").append(content).append("```\n\n");
                    } catch (Exception e) {
                        log.log(Level.WARNING, "Error while adding source code:" + e.getMessage());
                    }
                });
            return sb.toString();
        }
    }
}
