package de.spraener.prjxp.common.code.java;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static de.spraener.prjxp.common.code.java.JavaCodeSection.*;

class JavaCodeSectionTest {

    @Test
    void fromName_knownValues() {
        assertThat(JavaCodeSection.fromName("imports")).isEqualTo(IMPORTS);
        assertThat(JavaCodeSection.fromName("methodDoc")).isEqualTo(METHOD_DOC);
        assertThat(JavaCodeSection.fromName("method")).isEqualTo(METHOD);
        assertThat(JavaCodeSection.fromName("classFrame")).isEqualTo(CLAZZ_FRAME);
        assertThat(JavaCodeSection.fromName("dependenciesInfo")).isEqualTo(DEPENDENCIE_INFO);
    }

    @Test
    void fromName_unknown_returnsUnknown() {
        assertThat(JavaCodeSection.fromName("xyz")).isEqualTo(UNKNOWN);
    }

    @Test
    void getName_returnsCorrectName() {
        assertThat(UNKNOWN.getName()).isEqualTo("unknown");
        assertThat(IMPORTS.getName()).isEqualTo("imports");
        assertThat(METHOD_DOC.getName()).isEqualTo("methodDoc");
        assertThat(METHOD.getName()).isEqualTo("method");
        assertThat(CLAZZ_FRAME.getName()).isEqualTo("classFrame");
        assertThat(DEPENDENCIE_INFO.getName()).isEqualTo("dependenciesInfo");
    }
}
