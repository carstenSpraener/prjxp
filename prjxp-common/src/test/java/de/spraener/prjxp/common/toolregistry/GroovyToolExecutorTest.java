package de.spraener.prjxp.common.toolregistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GroovyToolExecutorTest {

    private final GroovyToolExecutor executor = new GroovyToolExecutor();

    @TempDir
    Path tempDir;

    @Test
    void loadAndRegister_validToolIsRegistered(@TempDir Path dir) throws Exception {
        String script = """
                [
                    name: 'greet',
                    description: 'Greets a user',
                    parameters: ['name': 'User name'],
                    execute: { params -> "Hello, ${params['name']}!" }
                ]
                """;

        Files.writeString(dir.resolve("greet.groovy"), script);

        List<ToolDefinition> registered = new ArrayList<>();
        executor.loadAndRegister(dir, registered::add);

        assertThat(registered).hasSize(1);
        assertThat(registered.get(0).getName()).isEqualTo("greet");
        assertThat(registered.get(0).getDescription()).isEqualTo("Greets a user");
        assertThat(registered.get(0).getParameters()).containsEntry("name", "User name");
    }

    @Test
    void loadAndRegister_missingDirectoryDoesNotCrash() {
        Path nonExistent = tempDir.resolve("does-not-exist");
        List<ToolDefinition> registered = new ArrayList<>();

        // Should not throw, just log a warning
        executor.loadAndRegister(nonExistent, registered::add);

        assertThat(registered).isEmpty();
    }

    @Test
    void loadAndRegister_invalidScriptWithoutNameIsSkipped(@TempDir Path dir) throws Exception {
        String script = """
                [
                    description: 'No name tool',
                    parameters: [:],
                    execute: { params -> "ok" }
                ]
                """;

        Files.writeString(dir.resolve("bad.groovy"), script);

        List<ToolDefinition> registered = new ArrayList<>();
        executor.loadAndRegister(dir, registered::add);

        assertThat(registered).isEmpty(); // Script should be skipped due to missing 'name'
    }

    @Test
    void loadAndRegister_invalidScriptWithoutDescriptionIsSkipped(@TempDir Path dir) throws Exception {
        String script = """
                [
                    name: 'no-desc',
                    parameters: [:],
                    execute: { params -> "ok" }
                ]
                """;

        Files.writeString(dir.resolve("bad.groovy"), script);

        List<ToolDefinition> registered = new ArrayList<>();
        executor.loadAndRegister(dir, registered::add);

        assertThat(registered).isEmpty(); // Script should be skipped due to missing 'description'
    }

    @Test
    void loadAndRegister_invalidScriptWithoutExecuteClosureIsSkipped(@TempDir Path dir) throws Exception {
        String script = """
                [
                    name: 'no-execute',
                    description: 'Missing execute closure',
                    parameters: [:]
                ]
                """;

        Files.writeString(dir.resolve("bad.groovy"), script);

        List<ToolDefinition> registered = new ArrayList<>();
        executor.loadAndRegister(dir, registered::add);

        assertThat(registered).isEmpty(); // Script should be skipped due to missing 'execute'
    }

    @Test
    void loadAndRegister_scriptNotReturningMapIsSkipped(@TempDir Path dir) throws Exception {
        String script = "return 'not a map'";

        Files.writeString(dir.resolve("bad.groovy"), script);

        List<ToolDefinition> registered = new ArrayList<>();
        executor.loadAndRegister(dir, registered::add);

        assertThat(registered).isEmpty();
    }

    @Test
    void executeClosureReceivesParams(@TempDir Path dir) throws Exception {
        String script = """
                [
                    name: 'echo',
                    description: 'Echoes input',
                    parameters: ['msg': 'Message to echo'],
                    execute: { params -> "Echo: ${params['msg']}" }
                ]
                """;

        Files.writeString(dir.resolve("echo.groovy"), script);

        List<ToolDefinition> registered = new ArrayList<>();
        executor.loadAndRegister(dir, registered::add);

        assertThat(registered).hasSize(1);
        String result = registered.get(0).getExecute().apply(Map.of("msg", "Hello World"));
        assertThat(result).isEqualTo("Echo: Hello World");
    }

    @Test
    void executeClosureCanAccessInjectedContext(@TempDir Path dir) throws Exception {
        String script = """
                [
                    name: 'path-check',
                    description: 'Checks injected projectRoot',
                    parameters: [:],
                    execute: { params -> "projectRoot=${projectRoot}" }
                ]
                """;

        Files.writeString(dir.resolve("path.groovy"), script);

        List<ToolDefinition> registered = new ArrayList<>();
        executor.loadAndRegister(dir, registered::add);

        assertThat(registered).hasSize(1);
        String result = registered.get(0).getExecute().apply(Map.of());
        assertThat(result).contains("projectRoot=");
    }

    @Test
    void loadAndRegister_ignoresNonGroovyFiles(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("script.txt"), "not groovy");
        Files.writeString(dir.resolve("readme.md"), "# readme");

        List<ToolDefinition> registered = new ArrayList<>();
        executor.loadAndRegister(dir, registered::add);

        assertThat(registered).isEmpty();
    }

    @Test
    void loadAndRegister_multipleGroovyFiles(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.groovy"), """
                [name: 'tool-a', description: 'A', parameters: [:], execute: { p -> 'a' }]
                """);
        Files.writeString(dir.resolve("b.groovy"), """
                [name: 'tool-b', description: 'B', parameters: [:], execute: { p -> 'b' }]
                """);

        List<ToolDefinition> registered = new ArrayList<>();
        executor.loadAndRegister(dir, registered::add);

        assertThat(registered).hasSize(2);
        assertThat(registered.get(0).getName()).isEqualTo("tool-a");
        assertThat(registered.get(1).getName()).isEqualTo("tool-b");
    }

    @Test
    void executeClosureWithNullResultReturnsEmptyString(@TempDir Path dir) throws Exception {
        String script = """
                [
                    name: 'null-return',
                    description: 'Returns null',
                    parameters: [:],
                    execute: { params -> null }
                ]
                """;

        Files.writeString(dir.resolve("null.groovy"), script);

        List<ToolDefinition> registered = new ArrayList<>();
        executor.loadAndRegister(dir, registered::add);

        assertThat(registered).hasSize(1);
        String result = registered.get(0).getExecute().apply(Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    void loadAndRegister_handlesCompilationErrorGracefully(@TempDir Path dir) throws Exception {
        // Syntax error in Groovy script
        String script = "this is not valid groovy {{{";

        Files.writeString(dir.resolve("broken.groovy"), script);

        List<ToolDefinition> registered = new ArrayList<>();
        // Should not throw, just log error and skip the file
        executor.loadAndRegister(dir, registered::add);

        assertThat(registered).isEmpty();
    }
}
