package de.spraener.prjxp.common.config;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPJsonStreamProvider;
import de.spraener.prjxp.common.test.PrjXPTestObjectMother;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;

@SpringBootTest
@ActiveProfiles("test")
class PrjXPJsonStreamProviderTest {

    @Autowired
    private PrjXPJsonStreamProvider uut;

    @Test
    void getJsonlStream_withEmptyString_doesNotThrow() {
        assertThatCode(() -> uut.getJsonlStream("")).doesNotThrowAnyException();
    }

    @Test
    void getJsonlStream_withNull_doesNotThrow() {
        assertThatCode(() -> uut.getJsonlStream(null)).doesNotThrowAnyException();
    }

    @Test
    void getJsonlStream_withDash_doesNotThrow() {
        assertThatCode(() -> uut.getJsonlStream("-")).doesNotThrowAnyException();
    }

    @Configuration
    static class TestConfig {
        @Bean
        PrjXPConfig prjXPConfig() {
            return PrjXPTestObjectMother.createPrjXPConfig();
        }

        @Bean
        PrjXPJsonStreamProvider uut(PrjXPConfig config) {
            return new PrjXPJsonStreamProvider(config);
        }
    }
}
