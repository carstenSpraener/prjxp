package de.spraener.prjxp.docpipe.prompt.groovy;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class DPGroovyTool {
    public record GrepHit(File file, int line, String lineText) {}

    public DPGroovyTool grep(Path rootDir, String regex, Consumer<GrepHit> onHit) throws IOException {
        Pattern pattern = Pattern.compile(regex);
        Files.walk(rootDir)
                .filter(Files::isRegularFile)
                .map(Path::toFile)
                .forEach(f -> {
                    try (LineIterator it = FileUtils.lineIterator(f, "UTF-8")) {
                        int lineNumber = 0;
                        while (it.hasNext()) {
                            String line = it.nextLine();
                            if (pattern.matcher(line).find()) {
                                onHit.accept(new GrepHit(f, lineNumber, line));
                            }
                            lineNumber++;
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
        );
        return this;
    }

    public String printFile(File file) throws IOException {
        return FileUtils.readFileToString(file, StandardCharsets.UTF_8);
    }
}
