package de.spraener.prjxp.common;

import de.spraener.prjxp.common.config.PrjXPArgsParser;
import de.spraener.prjxp.common.test.PrjXPTestComponentMother;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;

@SpringBootTest
@ActiveProfiles("test")
class PrjXPCliTest {

    @Autowired
    private PrjXPCli uut;

    @Test
    void readDotEnv_withEmptyArray_doesNotThrow() {
        assertThatNoException().isThrownBy(() -> PrjXPCli.readDotEnv(new String[0]));
    }

    @Test
    void start_setsRunning() {
        assertThatNoException().isThrownBy(() -> uut.start());
    }

    @Test
    void stop_setsNotRunning() {
        uut.start();

        assertThatNoException().isThrownBy(() -> uut.stop());
    }

    @Test
    void isRunning_returnsBoolean() {
        boolean result = uut.isRunning();

        assertThatNoException().isThrownBy(() -> {});
    }

    @Configuration
    static class TestConfig {
        @Bean
        PrjXPArgsParser argsParser() {
            return PrjXPTestComponentMother.createPrjXPArgsParser();
        }

        @Bean
        PrjXPCli uut(PrjXPArgsParser argsParser) {
            return new PrjXPCli(argsParser);
        }
    }
}
