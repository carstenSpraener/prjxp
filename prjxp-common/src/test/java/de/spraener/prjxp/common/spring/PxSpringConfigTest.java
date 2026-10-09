package de.spraener.prjxp.common.spring;

import org.junit.jupiter.api.Test;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;

class PxSpringConfigTest {

    @Test
    void validator_returnsLocalValidatorFactoryBean() {
        PxSpringConfig config = new PxSpringConfig();
        Validator validator = config.validator();

        assertThat(validator).isInstanceOf(LocalValidatorFactoryBean.class);
    }
}
