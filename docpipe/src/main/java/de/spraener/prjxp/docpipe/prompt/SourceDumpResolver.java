package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;
import lombok.extern.java.Log;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

@Component
@Log
public class SourceDumpResolver implements TemplateResolver {
    @Override
    public String getID() {
        return "java-src-dump";
    }

    @Override
    public String resolve(File baseDir, Options options) throws Exception {
        String srcDir = baseDir.getAbsolutePath()+"/"+options.param(0).toString();
        StringBuilder sb = new StringBuilder("\n");
        Files.walk(Path.of(srcDir))
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        String content = IOUtils.toString(new FileInputStream(path.toFile()), StandardCharsets.UTF_8);
                        sb.append("```java\n").append(content).append("```\n\n");
                    } catch( Exception e) {
                        log.log(Level.WARNING, "Error while adding source code:"+e.getMessage());
                    }
                });
        return sb.toString();
    }
}
