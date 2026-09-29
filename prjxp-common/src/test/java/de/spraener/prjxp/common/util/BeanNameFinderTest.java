package de.spraener.prjxp.common.util;

import de.spraener.prjxp.common.util.BeanNameFinder;
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
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class BeanNameFinderTest {

    @Autowired
    private BeanNameFinder uut;

    @Test
    void findBeanName_withNull_throwsNpe() {
        assertThatCode(() -> uut.findBeanName(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void findBeanName_withUnknownObject_returnsNull() {
        Object unknown = new Object();

        String result = uut.findBeanName(unknown);

        assertThatNoException().isThrownBy(() -> {});
    }

    @Configuration
    static class TestConfig {
        @Bean
        SpringContextSupplier springContextSupplier() {
            return new SpringContextSupplier();
        }

        @Bean
        BeanNameFinder uut(SpringContextSupplier springContextSupplier) {
            return new BeanNameFinder(springContextSupplier);
        }
    }
}
