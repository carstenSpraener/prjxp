package de.spraener.prjxp.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link ValueContainer}.
 */
class ValueContainerTest {

    @Test
    void getSetAndToString() {
        ValueContainer<String> container = new ValueContainer<>("a");

        assertThat(container.getValue()).isEqualTo("a");
        container.setValue("b");

        assertThat(container.getValue()).isEqualTo("b");
        assertThat(container.toString()).isEqualTo("b");
    }

    @Test
    void setValue_returnsSameInstance() {
        ValueContainer<String> container = new ValueContainer<>("a");

        ValueContainer<String> result = container.setValue("b");
        assertThat(result).isSameAs(container); // fluent: returns the same instance
    }
}
