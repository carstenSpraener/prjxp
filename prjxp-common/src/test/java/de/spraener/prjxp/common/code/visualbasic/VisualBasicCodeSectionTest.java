package de.spraener.prjxp.common.code.visualbasic;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static de.spraener.prjxp.common.code.visualbasic.VisualBasicCodeSection.*;

class VisualBasicCodeSectionTest {

    @Test
    void fromName_null_returnsUnknown() {
        assertThat(VisualBasicCodeSection.fromName(null)).isEqualTo(UNKNOWN);
    }

    @Test
    void fromName_knownValues() {
        assertThat(VisualBasicCodeSection.fromName("imports")).isEqualTo(IMPORTS);
        assertThat(VisualBasicCodeSection.fromName("methodDoc")).isEqualTo(METHOD_DOC);
        assertThat(VisualBasicCodeSection.fromName("method")).isEqualTo(METHOD);
        assertThat(VisualBasicCodeSection.fromName("classFrame")).isEqualTo(CLASS_FRAME);
    }

    @Test
    void fromName_unknown_returnsUnknown() {
        assertThat(VisualBasicCodeSection.fromName("xyz")).isEqualTo(UNKNOWN);
    }

    @Test
    void getName_returnsCorrectName() {
        assertThat(UNKNOWN.getName()).isEqualTo("unknown");
        assertThat(IMPORTS.getName()).isEqualTo("imports");
        assertThat(METHOD_DOC.getName()).isEqualTo("methodDoc");
        assertThat(METHOD.getName()).isEqualTo("method");
        assertThat(CLASS_FRAME.getName()).isEqualTo("classFrame");
    }
}
