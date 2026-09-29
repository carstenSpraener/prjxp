package de.spraener.prjxp.docpipe.prompt.groovy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DPGroovyTool} (grep + printFile), using a temporary
 * directory tree with small source files.
 */
class DPGroovyToolTest {

    @TempDir
    Path rootDir;

    private DPGroovyTool tool;

    @BeforeEach
    void setUp() throws Exception {
        tool = new DPGroovyTool();

        Files.writeString(rootDir.resolve("Alpha.java"),
                "line one TODO first\nplain line\nTODO second\n", StandardCharsets.UTF_8);
        Files.createDirectories(rootDir.resolve("sub"));
        Files.writeString(rootDir.resolve("sub").resolve("Beta.java"),
                "nothing to see\nTODO third\n", StandardCharsets.UTF_8);
        Files.writeString(rootDir.resolve("readme.txt"),
                "no matches in here\n", StandardCharsets.UTF_8);
    }

    @Test
    void grepFindsHitsAcrossFilesWithZeroBasedLineNumbers() throws Exception {
        List<DPGroovyTool.GrepHit> hits = new ArrayList<>();

        DPGroovyTool returned = tool.grep(rootDir, "TODO", hits::add);

        assertThat(returned).isSameAs(tool);
        assertThat(hits).hasSize(3);

        List<String> rendered = hits.stream()
                .map(h -> h.file().getName() + ":" + h.line())
                .sorted()
                .toList();
        assertThat(rendered).containsExactlyInAnyOrder("Alpha.java:0", "Alpha.java:2", "Beta.java:1");

        DPGroovyTool.GrepHit alphaFirst = hits.stream()
                .filter(h -> h.file().getName().equals("Alpha.java") && h.line() == 0)
                .findFirst().orElseThrow();
        assertThat(alphaFirst.lineText()).isEqualTo("line one TODO first");

        DPGroovyTool.GrepHit beta = hits.stream()
                .filter(h -> h.file().getName().equals("Beta.java"))
                .findFirst().orElseThrow();
        assertThat(beta.lineText()).isEqualTo("TODO third");
    }

    @Test
    void grepScansAllRegularFilesIncludingNonJava() throws Exception {
        List<DPGroovyTool.GrepHit> hits = new ArrayList<>();

        tool.grep(rootDir, "matches", hits::add);

        // the pattern only occurs in readme.txt (not a Java file) -> all regular files are scanned
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).file().getName()).isEqualTo("readme.txt");
        assertThat(hits.get(0).line()).isZero();
    }

    @Test
    void grepWithoutMatchesInvokesConsumerNever() throws Exception {
        List<DPGroovyTool.GrepHit> hits = new ArrayList<>();

        DPGroovyTool returned = tool.grep(rootDir, "NO_SUCH_TOKEN", hits::add);

        assertThat(returned).isSameAs(tool);
        assertThat(hits).isEmpty();
    }

    @Test
    void grepWrapsIoFailureInRuntimeException() throws Exception {
        Path unreadable = rootDir.resolve("locked.java");
        Files.writeString(unreadable, "TODO locked\n", StandardCharsets.UTF_8);
        // strip all permissions -> opening the file fails with an IOException
        Files.setPosixFilePermissions(unreadable, EnumSet.noneOf(PosixFilePermission.class));

        List<DPGroovyTool.GrepHit> hits = new ArrayList<>();

        assertThatThrownBy(() -> tool.grep(rootDir, "TODO", hits::add))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void printFileReturnsFullContent() throws Exception {
        File alpha = rootDir.resolve("Alpha.java").toFile();

        String content = tool.printFile(alpha);

        assertThat(content).isEqualTo(Files.readString(alpha.toPath()));
        assertThat(content).contains("line one TODO first");
    }
}
