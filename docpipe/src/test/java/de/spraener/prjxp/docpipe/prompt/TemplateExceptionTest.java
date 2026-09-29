package de.spraener.prjxp.docpipe.prompt;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TemplateException}.
 */
class TemplateExceptionTest {

    @Test
    void messageConstructor() {
        TemplateException ex = new TemplateException("template failed");

        assertThat(ex.getMessage()).isEqualTo("template failed");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void messageAndCauseConstructor() {
        RuntimeException cause = new RuntimeException("root problem");

        TemplateException ex = new TemplateException("template failed", cause);

        assertThat(ex.getMessage()).isEqualTo("template failed");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void causeOnlyConstructor() {
        IllegalStateException cause = new IllegalStateException("kaboom");

        TemplateException ex = new TemplateException(cause);

        assertThat(ex.getCause()).isSameAs(cause);
        // RuntimeException(Throwable) derives the message from the cause
        assertThat(ex.getMessage()).contains("kaboom");
    }

    @Test
    void isRuntimeException() {
        assertThat(new TemplateException("x")).isInstanceOf(RuntimeException.class);
    }
}
