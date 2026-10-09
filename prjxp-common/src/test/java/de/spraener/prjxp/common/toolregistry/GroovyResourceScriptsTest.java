package de.spraener.prjxp.common.toolregistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that validate the actual Groovy script files in src/main/resources/groovyTools/.
 * These tests ensure the resource scripts are loadable and functional on any platform (including CI).
 */
class GroovyResourceScriptsTest {

    private final GroovyToolExecutor executor = new GroovyToolExecutor();
    private final ToolRegistry registry = new ToolRegistry(executor);

    @TempDir
    Path tempDir;

    /**
     * Copy all .groovy files from the groovyTools resource directory to a temp directory.
     * This is necessary because resources inside JARs cannot be accessed as filesystem paths.
     */
    private Path copyGroovyResourcesToTemp() throws Exception {
        URL resourceUrl = getClass().getClassLoader().getResource("groovyTools");
        if (resourceUrl == null) {
            throw new IllegalStateException("groovyTools resource directory not found on classpath");
        }

        // Get the protocol to determine how to access the resources
        String protocol = resourceUrl.getProtocol();

        if ("file".equals(protocol)) {
            // Resources are on the filesystem (local development)
            Path sourceDir = Path.of(resourceUrl.toURI());
            if (Files.isDirectory(sourceDir)) {
                // Copy all .groovy files to temp directory
                try (var stream = Files.list(sourceDir)) {
                    stream.filter(p -> p.toString().endsWith(".groovy"))
                          .forEach(source -> {
                              try {
                                  Files.copy(source, tempDir.resolve(source.getFileName().toString()));
                              } catch (Exception e) {
                                  throw new RuntimeException("Failed to copy " + source, e);
                              }
                          });
                }
                return tempDir;
            }
        }

        // Fallback: Try to load individual resource files by name
        // This works for resources inside JARs
        String[] scriptNames = {"fileRead.groovy", "dirList.groovy", "TEMPLATE.groovy"};
        for (String name : scriptNames) {
            InputStream is = getClass().getClassLoader().getResourceAsStream("groovyTools/" + name);
            if (is != null) {
                try {
                    Files.copy(is, tempDir.resolve(name));
                } finally {
                    is.close();
                }
            } else {
                throw new IllegalStateException("Resource not found: groovyTools/" + name);
            }
        }

        return tempDir;
    }

    @Test
    void resourceScripts_areLoadableFromClasspath() throws Exception {
        Path groovyDir = copyGroovyResourcesToTemp();

        List<ToolDefinition> tools = new ArrayList<>();
        executor.loadAndRegister(groovyDir, tools::add);

        // Should have loaded fileRead.groovy, dirList.groovy, and TEMPLATE.groovy (3 tools)
        assertThat(tools).hasSize(3);

        // Verify tool names
        List<String> names = tools.stream().map(ToolDefinition::getName).toList();
        assertThat(names).contains("fileRead");
        assertThat(names).contains("dirList");
        assertThat(names).contains("myTool"); // TEMPLATE.groovy defines "myTool"
    }

    @Test
    void fileRead_resourceScript_canReadFile() throws Exception {
        Path groovyDir = copyGroovyResourcesToTemp();

        List<ToolDefinition> tools = new ArrayList<>();
        executor.loadAndRegister(groovyDir, tools::add);

        ToolDefinition fileRead = tools.stream()
                .filter(t -> "fileRead".equals(t.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("fileRead tool not found"));

        // Create a temp file to read
        Path testFile = Files.createTempFile("fileRead_test_", ".txt");
        try {
            Files.writeString(testFile, "Test content for fileRead resource script");

            String result = fileRead.getExecute().apply(Map.of("path", testFile.toString()));
            assertThat(result).contains("Test content for fileRead resource script");
        } finally {
            Files.delete(testFile);
        }
    }

    @Test
    void dirList_resourceScript_canListDirectory() throws Exception {
        Path groovyDir = copyGroovyResourcesToTemp();

        List<ToolDefinition> tools = new ArrayList<>();
        executor.loadAndRegister(groovyDir, tools::add);

        ToolDefinition dirList = tools.stream()
                .filter(t -> "dirList".equals(t.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("dirList tool not found"));

        // List the temp directory itself (which contains the copied groovy files)
        String result = dirList.getExecute().apply(Map.of("path", groovyDir.toString()));

        assertThat(result).contains("Contents of");
        assertThat(result).contains("fileRead.groovy");
        assertThat(result).contains("dirList.groovy");
    }

    @Test
    void templateResourceScript_isLoadableAndExecutable() throws Exception {
        Path groovyDir = copyGroovyResourcesToTemp();

        List<ToolDefinition> tools = new ArrayList<>();
        executor.loadAndRegister(groovyDir, tools::add);

        ToolDefinition template = tools.stream()
                .filter(t -> "myTool".equals(t.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("myTool (TEMPLATE.groovy) not found"));

        // Execute should return the default result
        String result = template.getExecute().apply(Map.of());
        assertThat(result).isEqualTo("Your implementation here");
    }

    @Test
    void registry_loadGroovyTools_fromResourceDirectory() throws Exception {
        Path groovyDir = copyGroovyResourcesToTemp();

        registry.loadGroovyTools(groovyDir);

        // Should have loaded 3 tools
        assertThat(registry.getTools()).hasSize(3);

        List<String> descriptions = registry.getToolDescriptions();
        assertThat(descriptions).hasSize(3);

        // Verify tool names are present in descriptions
        String allDescriptions = String.join("\n", descriptions);
        assertThat(allDescriptions).contains("fileRead");
        assertThat(allDescriptions).contains("dirList");
        assertThat(allDescriptions).contains("myTool");
    }

    @Test
    void resourceScripts_handleMissingFilesGracefully() throws Exception {
        Path groovyDir = copyGroovyResourcesToTemp();

        List<ToolDefinition> tools = new ArrayList<>();
        executor.loadAndRegister(groovyDir, tools::add);

        ToolDefinition fileRead = tools.stream()
                .filter(t -> "fileRead".equals(t.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("fileRead tool not found"));

        // Request a non-existent file
        String result = fileRead.getExecute().apply(Map.of("path", "/nonexistent/path/to/file.txt"));
        assertThat(result).contains("Error: File not found");
    }

    @Test
    void resourceScripts_handleMissingDirectoriesGracefully() throws Exception {
        Path groovyDir = copyGroovyResourcesToTemp();

        List<ToolDefinition> tools = new ArrayList<>();
        executor.loadAndRegister(groovyDir, tools::add);

        ToolDefinition dirList = tools.stream()
                .filter(t -> "dirList".equals(t.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("dirList tool not found"));

        // Request a non-existent directory
        String result = dirList.getExecute().apply(Map.of("path", "/nonexistent/directory"));
        assertThat(result).contains("Error: Directory not found");
    }

    @Test
    void resourceScripts_sandboxedContext_isAvailable() throws Exception {
        Path groovyDir = copyGroovyResourcesToTemp();

        List<ToolDefinition> tools = new ArrayList<>();
        executor.loadAndRegister(groovyDir, tools::add);

        // All loaded tools should have access to sandboxed context (projectRoot, baseDir, log)
        // We verify this by checking that the tools can be executed without errors
        for (ToolDefinition tool : tools) {
            // Each tool should be executable without throwing exceptions related to missing context
            try {
                String result = tool.getExecute().apply(Map.of());
                // If we get here, the sandboxed context is available
            } catch (Exception e) {
                // Some tools may throw errors for missing parameters, but not for missing context
                assertThat(e.getMessage()).doesNotContain("projectRoot");
                assertThat(e.getMessage()).doesNotContain("baseDir");
            }
        }
    }
}
