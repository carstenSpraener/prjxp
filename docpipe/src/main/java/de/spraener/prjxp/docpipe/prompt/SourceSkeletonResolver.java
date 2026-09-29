package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.io.IOUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
@Log
public class SourceSkeletonResolver implements TemplateResolver {
    private final List<SourceSkeletonizer> skeletonizers;

    @Override
    public String getID() {
        return "src-skeleton";
    }

    @Override
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        final Path srcPath = baseDir.toPath().resolve(firstParamOrContext(context, options));
        final boolean scanSubs = options.hash("scanSubs", true);
        final String ending = options.hash("ending", "vb");
        StringBuilder sb = new StringBuilder("\n");
        try (Stream<Path> walk = scanSubs ? Files.walk(srcPath) : Files.walk(srcPath, 1)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(ending))
                    .forEach(path -> appendSkeletonOrFallback(path, ending, sb));
            return sb.toString();
        }
    }

    private void appendSkeletonOrFallback(Path path, String ending, StringBuilder sb) {
        File sourceFile = path.toFile();
        Optional<SourceSkeletonizer> skeletonizer = skeletonizers.stream()
                .filter(s -> s.supports(sourceFile, ending))
                .findFirst();
        if (skeletonizer.isEmpty()) {
            log.warning("No source skeletonizer found for " + sourceFile.getAbsolutePath());
            appendFallbackDump(sourceFile, ending, "no skeletonizer for " + sourceFile.getPath(), sb);
            return;
        }
        try {
            sb.append("```").append(ending).append("\n")
                    .append(skeletonizer.get().skeletonize(sourceFile))
                    .append("```\n\n");
        } catch (Exception e) {
            log.log(Level.SEVERE, "Source skeletonizer failed for " + sourceFile.getAbsolutePath() + ": " + e.getMessage(), e);
            appendFallbackDump(sourceFile, ending, "skeletonizer failed for " + sourceFile.getPath(), sb);
        }
    }

    private void appendFallbackDump(File sourceFile, String ending, String reason, StringBuilder sb) {
        sb.append("<!-- ").append(reason).append(" -->\n");
        try (FileInputStream fis = new FileInputStream(sourceFile)) {
            String content = IOUtils.toString(fis, StandardCharsets.UTF_8);
            sb.append("```").append(ending).append("\n").append(content).append("```\n\n");
        } catch (Exception e) {
            log.log(Level.SEVERE, "Could not add fallback source dump for " + sourceFile.getAbsolutePath() + ": " + e.getMessage(), e);
        }
    }

    private String firstParamOrContext(Object context, Options options) {
        try {
            return options.param(0).toString();
        } catch (ArrayIndexOutOfBoundsException aioobXC) {
            return context.toString();
        }
    }
}
