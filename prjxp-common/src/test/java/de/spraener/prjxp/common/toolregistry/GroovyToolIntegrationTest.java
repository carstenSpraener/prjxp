package de.spraener.prjxp.common.toolregistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GroovyToolIntegrationTest {

    private final GroovyToolExecutor executor = new GroovyToolExecutor();
    private final ToolRegistry registry = new ToolRegistry(executor);

    @TempDir
    Path tempDir;

    // === fileRead.groovy content (copied from resources) ===
    private static final String FILE_READ_SCRIPT =
        "def name = \"fileRead\"\n" +
        "def description = \"Read the full content of a source file. Use this when you need to examine specific files in detail, especially after reviewing source code skeletons.\"\n" +
        "def parameters = [\n" +
        "    path: \"Path to the file (relative to project root or absolute)\"\n" +
        "]\n" +
        "\n" +
        "def execute(params) {\n" +
        "    def path = params.path\n" +
        "    if (!path) return \"Error: No path provided\"\n" +
        "\n" +
        "    // Handle both absolute and relative paths\n" +
        "    def file = new File(path)\n" +
        "    if (!file.isAbsolute()) {\n" +
        "        file = new File(projectRoot.toFile(), path)\n" +
        "    }\n" +
        "    if (!file.exists()) return \"Error: File not found: ${path}\"\n" +
        "    if (!file.isFile()) return \"Error: Not a file: ${path}\"\n" +
        "\n" +
        "    def content = file.text\n" +
        "    // Detect language from extension for code block formatting\n" +
        "    def ext = path.contains('.') ? path.substring(path.lastIndexOf('.')) : ''\n" +
        "    return \"```${ext}\\n${content}\\n```\"\n" +
        "}\n" +
        "\n" +
        "[name: name, description: description, parameters: parameters, execute: this.&execute]\n";

    // === dirList.groovy content (copied from resources) ===
    private static final String DIR_LIST_SCRIPT =
        "def name = \"dirList\"\n" +
        "def description = \"List files and directories in a given path. Useful for exploring project structure and finding relevant files to read.\"\n" +
        "def parameters = [\n" +
        "    path: \"Directory path (relative to project root or absolute). Use '.' for the project root.\"\n" +
        "]\n" +
        "\n" +
        "def execute(params) {\n" +
        "    def path = params.path ?: '.'\n" +
        "    // Handle both absolute and relative paths\n" +
        "    def dir = new File(path)\n" +
        "    if (!dir.isAbsolute()) {\n" +
        "        dir = new File(projectRoot.toFile(), path)\n" +
        "    }\n" +
        "\n" +
        "    if (!dir.exists()) return \"Error: Directory not found: ${path}\"\n" +
        "    if (!dir.isDirectory()) return \"Error: Not a directory: ${path}\"\n" +
        "\n" +
        "    def entries = dir.listFiles()\n" +
        "    if (entries == null || entries.length == 0) return \"Directory '${path}' is empty.\"\n" +
        "\n" +
        "    entries = entries.sort { it.name }\n" +
        "    StringBuilder sb = new StringBuilder()\n" +
        "    sb.append(\"Contents of '${path}':\\n\")\n" +
        "    for (entry in entries) {\n" +
        "        def prefix = entry.isDirectory() ? \"[DIR]  \" : \"       \"\n" +
        "        sb.append(prefix).append(entry.name).append(\"\\n\")\n" +
        "    }\n" +
        "    return sb.toString()\n" +
        "}\n" +
        "\n" +
        "[name: name, description: description, parameters: parameters, execute: this.&execute]\n";

    // === TEMPLATE.groovy content (copied from resources) ===
    private static final String TEMPLATE_SCRIPT =
        "def name = \"myTool\"\n" +
        "def description = \"Describe what this tool does and when the LLM should use it. Be specific about the use case and any limitations.\"\n" +
        "\n" +
        "def parameters = [\n" +
        "    param1: \"Description of parameter 1\",\n" +
        "    param2: \"Description of parameter 2 (optional)\"\n" +
        "]\n" +
        "\n" +
        "def execute(params) {\n" +
        "    // Access sandboxed context variables:\n" +
        "    // - projectRoot (Path): The project root directory\n" +
        "    // - baseDir (Path): Working directory\n" +
        "    // - configSubset (ConfigSubset): Safe subset of configuration\n" +
        "    // - log (Logger): SLF4J logger for debug output\n" +
        "\n" +
        "    def result = \"Your implementation here\"\n" +
        "    return result   // Must return a String\n" +
        "}\n" +
        "\n" +
        "[name: name, description: description, parameters: parameters, execute: this.&execute]\n";

    @Test
    void fileRead_toolCanBeLoadedAndExecuted(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("fileRead.groovy"), FILE_READ_SCRIPT);

        List<ToolDefinition> tools = new ArrayList<>();
        executor.loadAndRegister(dir, tools::add);

        assertThat(tools).hasSize(1);
        ToolDefinition tool = tools.get(0);
        assertThat(tool.getName()).isEqualTo("fileRead");
        assertThat(tool.getDescription()).contains("Read the full content of a source file");

        // Test execution: create a test file and read it
        Path testFile = dir.resolve("test.txt");
        Files.writeString(testFile, "Hello World");

        String result = tool.getExecute().apply(Map.of("path", testFile.toString()));
        assertThat(result).contains("Hello World");
    }

    @Test
    void fileRead_nonExistentFile_returnsError(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("fileRead.groovy"), FILE_READ_SCRIPT);

        List<ToolDefinition> tools = new ArrayList<>();
        executor.loadAndRegister(dir, tools::add);

        String result = tools.get(0).getExecute().apply(Map.of("path", "/nonexistent/path.txt"));
        assertThat(result).contains("Error: File not found");
    }

    @Test
    void dirList_toolCanBeLoadedAndExecuted(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("dirList.groovy"), DIR_LIST_SCRIPT);

        List<ToolDefinition> tools = new ArrayList<>();
        executor.loadAndRegister(dir, tools::add);

        assertThat(tools).hasSize(1);
        ToolDefinition tool = tools.get(0);
        assertThat(tool.getName()).isEqualTo("dirList");
        assertThat(tool.getDescription()).contains("List files and directories");

        // Test execution: list the temp directory using absolute path
        String result = tool.getExecute().apply(Map.of("path", dir.toString()));
        assertThat(result).contains("Contents of");
    }

    @Test
    void dirList_nonExistentDirectory_returnsError(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("dirList.groovy"), DIR_LIST_SCRIPT);

        List<ToolDefinition> tools = new ArrayList<>();
        executor.loadAndRegister(dir, tools::add);

        String result = tools.get(0).getExecute().apply(Map.of("path", "/nonexistent/path"));
        assertThat(result).contains("Error: Directory not found");
    }

    @Test
    void templateGroovy_isLoadable(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("TEMPLATE.groovy"), TEMPLATE_SCRIPT);

        List<ToolDefinition> tools = new ArrayList<>();
        executor.loadAndRegister(dir, tools::add);

        assertThat(tools).hasSize(1);
        ToolDefinition tool = tools.get(0);
        assertThat(tool.getName()).isEqualTo("myTool");

        // Execute should return the default result
        String result = tool.getExecute().apply(Map.of());
        assertThat(result).isEqualTo("Your implementation here");
    }

    @Test
    void sandboxedContext_isAvailableInExecuteClosure(@TempDir Path dir) throws Exception {
        // Create a test script that uses sandboxed context variables
        String script =
            "def name = \"contextTest\"\n" +
            "def description = \"Tests sandboxed context variables\"\n" +
            "def parameters = [:]\n" +
            "\n" +
            "def execute(params) {\n" +
            "    StringBuilder sb = new StringBuilder()\n" +
            "    sb.append(\"projectRoot: ${projectRoot}\\n\")\n" +
            "    sb.append(\"baseDir: ${baseDir}\\n\")\n" +
            "    sb.append(\"log class: ${log?.class?.name ?: 'null'}\\n\")\n" +
            "    return sb.toString()\n" +
            "}\n" +
            "\n" +
            "[name: name, description: description, parameters: parameters, execute: this.&execute]\n";

        Files.writeString(dir.resolve("contextTest.groovy"), script);

        List<ToolDefinition> tools = new ArrayList<>();
        executor.loadAndRegister(dir, tools::add);

        assertThat(tools).hasSize(1);
        String result = tools.get(0).getExecute().apply(Map.of());

        assertThat(result).contains("projectRoot:");
        assertThat(result).contains("baseDir:");
        assertThat(result).doesNotContain("null"); // log should not be null
    }

    @Test
    void registry_loadGroovyTools_fromDirectory(@TempDir Path dir) throws Exception {
        // Write all three tool scripts to the directory
        Files.writeString(dir.resolve("fileRead.groovy"), FILE_READ_SCRIPT);
        Files.writeString(dir.resolve("dirList.groovy"), DIR_LIST_SCRIPT);
        Files.writeString(dir.resolve("TEMPLATE.groovy"), TEMPLATE_SCRIPT);

        registry.loadGroovyTools(dir);

        // Should have loaded 3 tools
        assertThat(registry.getTools()).hasSize(3);

        List<String> descriptions = registry.getToolDescriptions();
        assertThat(descriptions).hasSize(3);

        // Verify tool names are present in descriptions
        String allDescriptions = String.join("\n", descriptions);
        assertThat(allDescriptions).contains("fileRead");
        assertThat(allDescriptions).contains("dirList");
        assertThat(allDescriptions).contains("myTool"); // TEMPLATE.groovy defines "myTool"
    }

    @Test
    void registry_executeGroovyTool_directly(@TempDir Path dir) throws Exception {
        // Create a test file for fileRead to read
        Path testFile = dir.resolve("test.txt");
        Files.writeString(testFile, "Test content here");

        // Write fileRead.groovy to temp dir
        Files.writeString(dir.resolve("fileRead.groovy"), FILE_READ_SCRIPT);

        // Load into registry
        registry.loadGroovyTools(dir);

        // Execute via registry
        String result = registry.execute("fileRead", Map.of("path", testFile.toString()));
        assertThat(result).contains("Test content here");
    }
}
