package de.spraener.prjxp.mcp.testsupport;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public final class TestProcess {
    private TestProcess() {}

    public static List<String> git(Path config, int exitCode, Path logFile) throws IOException, URISyntaxException {
        Properties properties = new Properties();
        properties.setProperty("mode", "git");
        properties.setProperty("exit-code", Integer.toString(exitCode));
        if (logFile != null) properties.setProperty("log-file", logFile.toString());
        return write(config, properties);
    }

    public static List<String> timeout(Path config, Path pidFile) throws IOException, URISyntaxException {
        Properties properties = new Properties();
        properties.setProperty("mode", "timeout");
        properties.setProperty("pid-file", pidFile.toString());
        return write(config, properties);
    }

    public static List<String> output(Path config, String output, int exitCode) throws IOException, URISyntaxException {
        Properties properties = new Properties();
        properties.setProperty("mode", "output");
        properties.setProperty("output", output);
        properties.setProperty("exit-code", Integer.toString(exitCode));
        return write(config, properties);
    }

    private static List<String> write(Path config, Properties properties) throws IOException, URISyntaxException {
        try (var writer = Files.newBufferedWriter(config)) {
            properties.store(writer, null);
        }
        boolean windows = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        Path classes = Path.of(TestProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return List.of(java.toString(), "-cp", classes.toString(), TestProcess.class.getName(), config.toString());
    }

    public static void main(String[] args) throws Exception {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[0]))) {
            properties.load(reader);
        }
        int exitCode = Integer.parseInt(properties.getProperty("exit-code", "0"));
        switch (properties.getProperty("mode")) {
            case "output" -> System.out.print(properties.getProperty("output"));
            case "timeout" -> {
                Files.writeString(Path.of(properties.getProperty("pid-file")), Long.toString(ProcessHandle.current().pid()));
                System.out.close();
                System.err.close();
                Thread.sleep(5_000);
            }
            case "git" -> {
                List<String> command = Arrays.asList(Arrays.copyOfRange(args, 1, args.length));
                String logFile = properties.getProperty("log-file");
                if (logFile != null) Files.writeString(Path.of(logFile), String.join(" ", command) + System.lineSeparator(),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                if (command.contains("clone")) {
                    Path target = Path.of(command.get(command.size() - 1));
                    Files.createDirectories(target.resolve(".git"));
                    Files.writeString(target.resolve(".git-cloned"), "sentinel");
                    Files.writeString(target.resolve(".git-clone-ran"), "");
                    if (exitCode != 0) System.err.println("fatal: repository not found");
                } else if (command.contains("pull")) {
                    Path target = Path.of(command.get(command.indexOf("-C") + 1));
                    Files.writeString(target.resolve(".git-pull-ran"), "");
                    if (exitCode != 0) System.err.println("fatal: not a git repository");
                } else {
                    throw new IllegalArgumentException("Unsupported fake git invocation");
                }
            }
            default -> throw new IllegalArgumentException("Unsupported test process mode");
        }
        System.exit(exitCode);
    }
}
