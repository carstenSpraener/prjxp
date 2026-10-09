package de.spraener.prjxp.common.model;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PxFileTypeTest {

    @Test
    void from_javaExtension_returnsJavaCode() {
        assertThat(PxFileType.from(Path.of("Foo.java"))).isEqualTo(PxFileType.JAVA_CODE);
    }

    @Test
    void from_typescriptExtension_returnsTypeScriptCode() {
        assertThat(PxFileType.from(Path.of("bar.ts"))).isEqualTo(PxFileType.TYPESCRIPT_CODE);
    }

    @Test
    void from_visualBasicExtensions_returnVisualBasicCode() {
        assertThat(PxFileType.from(Path.of("a.vb"))).isEqualTo(PxFileType.VISUAL_BASIC_CODE);
        assertThat(PxFileType.from(Path.of("a.bas"))).isEqualTo(PxFileType.VISUAL_BASIC_CODE);
        assertThat(PxFileType.from(Path.of("a.cls"))).isEqualTo(PxFileType.VISUAL_BASIC_CODE);
        assertThat(PxFileType.from(Path.of("a.frm"))).isEqualTo(PxFileType.VISUAL_BASIC_CODE);
    }

    @Test
    void from_extensionsDeclaredBeforeUnknown_returnMatchingType() {
        assertThat(PxFileType.from(Path.of("a.jsp"))).isEqualTo(PxFileType.JSP);
        assertThat(PxFileType.from(Path.of("a.xml"))).isEqualTo(PxFileType.XML);
        assertThat(PxFileType.from(Path.of("a.pdf"))).isEqualTo(PxFileType.PDF);
        assertThat(PxFileType.from(Path.of("a.js"))).isEqualTo(PxFileType.JAVA_SCRIPT);
        assertThat(PxFileType.from(Path.of("a.html"))).isEqualTo(PxFileType.HTML);
    }

    @Test
    void from_extensionsDeclaredAfterUnknown_fallThroughToUnknown() {
        // Actual source behavior: UNKNOWN("") is declared before TXT/WORD_DOCX/WORD_DOC/RTF/MARK_DOWN
        // and String.endsWith("") is always true, so from() returns UNKNOWN for these extensions.
        assertThat(PxFileType.from(Path.of("a.txt"))).isEqualTo(PxFileType.UNKNOWN);
        assertThat(PxFileType.from(Path.of("a.docx"))).isEqualTo(PxFileType.UNKNOWN);
        assertThat(PxFileType.from(Path.of("a.doc"))).isEqualTo(PxFileType.UNKNOWN);
        assertThat(PxFileType.from(Path.of("a.rtf"))).isEqualTo(PxFileType.UNKNOWN);
        assertThat(PxFileType.from(Path.of("a.md"))).isEqualTo(PxFileType.UNKNOWN);
    }

    @Test
    void from_unknownExtension_returnsUnknown() {
        assertThat(PxFileType.from(Path.of("Foo.xyz"))).isEqualTo(PxFileType.UNKNOWN);
    }

    @Test
    void matches_positiveAndNegative() {
        assertThat(PxFileType.JAVA_CODE.matches(new File("Foo.java"))).isTrue();
        assertThat(PxFileType.JAVA_CODE.matches(new File("Foo.txt"))).isFalse();

        // The five types unreachable via from() still match correctly through matches(File):
        assertThat(PxFileType.TXT.matches(new File("a.txt"))).isTrue();
        assertThat(PxFileType.WORD_DOCX.matches(new File("a.docx"))).isTrue();
        assertThat(PxFileType.WORD_DOC.matches(new File("a.doc"))).isTrue();
        assertThat(PxFileType.RTF.matches(new File("a.rtf"))).isTrue();
        assertThat(PxFileType.MARK_DOWN.matches(new File("a.md"))).isTrue();
    }
}
