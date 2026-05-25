package de.spraener.prjxp.common.config;

import de.spraener.prjxp.common.config.PrjXPArgsParser;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.test.PrjXPTestObjectMother;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class PrjXPArgsParserTest {

    @Autowired
    private PrjXPArgsParser uut;

    @Test
    void parse_withEmptyArray_returnsConfig() {
        PrjXPConfig result = uut.parse(new String[0]);

        assertThatNoException().isThrownBy(() -> {});
    }

    @Test
    void parse_withNullArray_returnsConfig() {
        PrjXPConfig result = uut.parse(null);

        assertThatNoException().isThrownBy(() -> {});
    }

    @Test
    void parse_withArgs_returnsConfig() {
        PrjXPConfig result = uut.parse(new String[]{"-p", "myproject"});

        assertThatNoException().isThrownBy(() -> {});
    }

    @Configuration
    static class TestConfig {
        @Bean
        PrjXPConfig prjXPConfig() {
            return PrjXPTestObjectMother.createPrjXPConfig();
        }

        @Bean
        ApplicationEventPublisher publisher() {
            return mock(ApplicationEventPublisher.class);
        }

        @Bean
        PrjXPArgsParser uut(PrjXPConfig prjXPConfig, ApplicationEventPublisher publisher) {
            return new PrjXPArgsParser(prjXPConfig, publisher);
        }
    }
}
