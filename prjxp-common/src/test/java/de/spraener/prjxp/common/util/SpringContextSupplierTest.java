package de.spraener.prjxp.common.util;

import de.spraener.prjxp.common.util.SpringContextSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatNoException;

@SpringBootTest
@ActiveProfiles("test")
class SpringContextSupplierTest {

    @Autowired
    private SpringContextSupplier uut;

    @Test
    void setApplicationContext_setsContext() {
        ApplicationContext mockContext = org.mockito.Mockito.mock(ApplicationContext.class);

        assertThatNoException().isThrownBy(() -> uut.setApplicationContext(mockContext));
    }

    @Test
    void getContext_returnsContext() {
        ApplicationContext mockContext = org.mockito.Mockito.mock(ApplicationContext.class);
        uut.setApplicationContext(mockContext);

        ApplicationContext result = uut.getContext();

        assertThatNoException().isThrownBy(() -> {});
    }

    @Configuration
    static class TestConfig {
        @Bean
        SpringContextSupplier uut() {
            return new SpringContextSupplier();
        }
    }
}
