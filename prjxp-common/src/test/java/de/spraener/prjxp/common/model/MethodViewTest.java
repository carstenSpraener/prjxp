package de.spraener.prjxp.common.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MethodViewTest {

    @Test
    void constructor_withValuesAndWithNullJavadocBody() {
        MethodView withValues = new MethodView("com.example.Foo#bar", "Foo.java", 10, 20, "javadoc", "body");
        assertThat(withValues.fqn()).isEqualTo("com.example.Foo#bar");
        assertThat(withValues.file()).isEqualTo("Foo.java");
        assertThat(withValues.lineFrom()).isEqualTo(10);
        assertThat(withValues.lineTo()).isEqualTo(20);
        assertThat(withValues.javadoc()).isEqualTo("javadoc");
        assertThat(withValues.body()).isEqualTo("body");

        MethodView withNulls = new MethodView("com.example.Foo#baz", "Foo.java", 1, 2, null, null);
        assertThat(withNulls.fqn()).isEqualTo("com.example.Foo#baz");
        assertThat(withNulls.javadoc()).isNull();
        assertThat(withNulls.body()).isNull();
    }
}
