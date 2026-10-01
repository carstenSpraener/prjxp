package de.spraener.prjxp.common.code.typescript;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static de.spraener.prjxp.common.code.typescript.TypeScriptCodeSection.*;

class TypeScriptCodeSectionTest {

    @Test
    void fromName_knownValues() {
        assertThat(TypeScriptCodeSection.fromName("imports")).isEqualTo(IMPORTS);
        assertThat(TypeScriptCodeSection.fromName("methodDoc")).isEqualTo(METHOD_DOC);
        assertThat(TypeScriptCodeSection.fromName("method")).isEqualTo(METHOD);
        assertThat(TypeScriptCodeSection.fromName("classFrame")).isEqualTo(CLASS_FRAME);
        assertThat(TypeScriptCodeSection.fromName("dependenciesInfo")).isEqualTo(DEPENDENCIE_INFO);
    }

    @Test
    void fromName_unknown_returnsUnknown() {
        assertThat(TypeScriptCodeSection.fromName("xyz")).isEqualTo(UNKNOWN);
    }

    @Test
    void getName_returnsCorrectName() {
        assertThat(UNKNOWN.getName()).isEqualTo("unknown");
        assertThat(IMPORTS.getName()).isEqualTo("imports");
        assertThat(METHOD_DOC.getName()).isEqualTo("methodDoc");
        assertThat(METHOD.getName()).isEqualTo("method");
        assertThat(CLASS_FRAME.getName()).isEqualTo("classFrame");
        assertThat(DEPENDENCIE_INFO.getName()).isEqualTo("dependenciesInfo");
    }
}
