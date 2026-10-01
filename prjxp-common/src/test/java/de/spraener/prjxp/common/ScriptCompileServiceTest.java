package de.spraener.prjxp.common;

import de.spraener.prjxp.common.scripting.ScriptCompileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ScriptCompileServiceTest {

    @Autowired
    private ScriptCompileService uut;

    @Test
    void createEngine_withJavaScript_returnsEngine() {
        ScriptEngine result = uut.createEngine("javascript");

        assertThatNoException().isThrownBy(() -> {});
    }

    @Test
    void createEngine_withGroovy_returnsEngine() {
        ScriptEngine result = uut.createEngine("groovy");

        assertThatNoException().isThrownBy(() -> {});
    }

    @Test
    void compile_scriptFile_returnsEvaluableScript() throws Exception {
        ScriptEngine engine = uut.createEngine("groovy");

        Path tempFile = Files.createTempFile("script", ".groovy");
        Files.writeString(tempFile, "def x = 1 + 1\n");

        CompiledScript compiled = uut.compile(tempFile, engine);
        assertThat(compiled).isNotNull();
        // Verify the script can be evaluated without error
        compiled.eval();
    }

    @Test
    void compile_stringWithModifier_appliesConsumer() throws Exception {
        ScriptEngine engine = uut.createEngine("groovy");

        Consumer<ScriptEngine> modifier = e -> e.put("k", "v");
        uut.compile("x", engine, modifier);

        assertThat(engine.get("k")).isEqualTo("v");
    }

    @Test
    void compile_stringWithoutModifiers_works() throws Exception {
        ScriptEngine engine = uut.createEngine("groovy");

        CompiledScript compiled = uut.compile("def x = 42", engine);
        assertThat(compiled).isNotNull();
    }

    @Configuration
    static class TestConfig {
        @Bean
        ScriptCompileService uut() {
            return new ScriptCompileService();
        }
    }
}
