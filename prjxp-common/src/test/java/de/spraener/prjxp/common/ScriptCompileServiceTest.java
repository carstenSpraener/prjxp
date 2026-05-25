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
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;

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

    @Configuration
    static class TestConfig {
        @Bean
        ScriptCompileService uut() {
            return new ScriptCompileService();
        }
    }
}
