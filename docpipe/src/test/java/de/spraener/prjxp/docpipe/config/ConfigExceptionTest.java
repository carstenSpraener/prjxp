package de.spraener.prjxp.docpipe.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigExceptionTest {

    @Test
    void messageConstructor_preservesMessage() {
        ConfigException ex = new ConfigException("boom");

        assertThat(ex.getMessage()).isEqualTo("boom");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void causeConstructor_preservesCause() {
        RuntimeException root = new RuntimeException("root-cause");

        ConfigException ex = new ConfigException(root);

        assertThat(ex.getCause()).isSameAs(root);
        // Exception(Throwable) delegates the message to cause.toString()
        assertThat(ex.getMessage()).isEqualTo("java.lang.RuntimeException: root-cause");
    }

    @Test
    void isRuntimeException_free() {
        assertThat(new ConfigException("x")).isInstanceOf(Exception.class);
    }
}
