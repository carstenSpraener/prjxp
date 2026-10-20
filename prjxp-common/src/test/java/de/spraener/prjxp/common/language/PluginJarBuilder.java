package de.spraener.prjxp.common.language;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Builds plugin JARs at test time: copies fixture sources from
 * {@code src/test/resources/plugin-fixtures/<fixture>/src/}, compiles them
 * against the test classpath (so fixtures link against the host API) and
 * packages the classes plus a {@code META-INF/services} entry into a JAR.
 */
final class PluginJarBuilder {

    private static final String SERVICE_FILE = "META-INF/services/de.spraener.prjxp.common.language.LanguagePackProvider";

    private PluginJarBuilder() {
    }

    /**
     * @param workDir        target directory for the JAR (and temp compile dirs)
     * @param fixture        fixture name (subdirectory under plugin-fixtures)
     * @param sources        source file paths relative to the fixture's src/ dir
     * @param providerClasses FQNs for the SPI service file; empty = no service file
     */
    static File build(Path workDir, String fixture, List<String> sources, String... providerClasses) throws IOException {
        Path srcDir = workDir.resolve(fixture + "-src");
        List<String> sourceFiles = new ArrayList<>();
        for (String source : sources) {
            Path target = srcDir.resolve(source);
            Files.createDirectories(target.getParent());
            try (InputStream in = resource(fixture, "src/" + source)) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            sourceFiles.add(target.toString());
        }

        Path classesDir = workDir.resolve(fixture + "-classes");
        Files.createDirectories(classesDir);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("No system Java compiler available (running on a JRE?)");
        }
        List<String> args = new ArrayList<>(List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-d", classesDir.toString()));
        args.addAll(sourceFiles);
        int rc = compiler.run(null, null, null, args.toArray(String[]::new));
        if (rc != 0) {
            throw new IllegalStateException("Fixture compilation failed: " + fixture);
        }

        File jarFile = workDir.resolve(fixture + ".jar").toFile();
        try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jarFile))) {
            putDirectory(out, classesDir, "");
            if (providerClasses.length > 0) {
                putEntry(out, SERVICE_FILE, String.join("\n", providerClasses) + "\n");
            }
        }
        return jarFile;
    }

    private static InputStream resource(String fixture, String path) throws IOException {
        InputStream in = PluginJarBuilder.class.getResourceAsStream("/plugin-fixtures/" + fixture + "/" + path);
        if (in == null) {
            throw new IOException("Missing fixture resource: plugin-fixtures/" + fixture + "/" + path);
        }
        return in;
    }

    private static void putDirectory(JarOutputStream out, Path dir, String prefix) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String name = prefix + entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    putDirectory(out, entry, name + "/");
                } else {
                    putEntry(out, name, Files.readAllBytes(entry));
                }
            }
        }
    }

    private static void putEntry(JarOutputStream out, String name, byte[] bytes) throws IOException {
        out.putNextEntry(new JarEntry(name));
        out.write(bytes);
        out.closeEntry();
    }

    private static void putEntry(JarOutputStream out, String name, String content) throws IOException {
        putEntry(out, name, content.getBytes(StandardCharsets.UTF_8));
    }
}
