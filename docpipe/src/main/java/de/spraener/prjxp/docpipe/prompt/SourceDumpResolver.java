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
public class SourceDumpResolver implements TemplateResolver {
    @Override
    public String getID() {
        return "java-src-dump";
    }

    @Override
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
