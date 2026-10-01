package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceDumpResolverTest {

    @Test
    void testResolve() throws Exception {
        System.out.println("Running in "+(new File(".").getAbsolutePath()));
        SourceDumpResolver resolver = new SourceDumpResolver();
        Options optionsMock = Mockito.mock(Options.class);
        when(optionsMock.param(any(Integer.class))).thenReturn("src/main/java");
        when(optionsMock.hash(any(), any())).thenAnswer(i -> i.getArgument(1));
        String prompt = resolver.resolve(new File("."), null, optionsMock);
    }

    @Test
    void testResolveSourceCodeDump() throws Exception {
        System.out.println("Running in "+(new File(".").getAbsolutePath()));
        SourceDumpResolver uut = new SourceDumpResolver();
        Options optionsMock = Mockito.mock(Options.class);
        when(optionsMock.param(any(Integer.class))).thenReturn("../chunk-norris/src/main/java");
        when(optionsMock.hash(any(), any())).thenAnswer(i -> i.getArgument(1));
        String dump = uut.resolve(new File("."), null, optionsMock);
        Assertions.assertThat(dump).isNotEmpty();

        // Test artifacts go to a temp dir, never into the source tree (src/test/tmp).
        // Files.writeString uses UTF-8 and closes its stream, so the artifact is complete.
        Path artifact = tempDir.resolve("src-dmp.txt");
        Files.writeString(artifact, dump);
        assertThat(Files.readString(artifact)).isEqualTo(dump);
    }

    @TempDir
    Path tempDir;

    private Options optionsFor(String path, boolean scanSubs) {
        Options optionsMock = mock(Options.class);
        when(optionsMock.param(any(Integer.class))).thenReturn(path);
        when(optionsMock.hash(anyString(), any())).thenAnswer(inv ->
                "scanSubs".equals(inv.getArgument(0)) ? scanSubs : inv.getArgument(1));
        return optionsMock;
    }

    @Test
    void testResolveWithoutScanSubsOnlyReadsTopLevel() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src").resolve("Top.java"), "class Top {}");
        Files.createDirectories(tempDir.resolve("src").resolve("nested"));
        Files.writeString(tempDir.resolve("src").resolve("nested").resolve("Deep.java"), "class Deep {}");

        SourceDumpResolver uut = new SourceDumpResolver();
        String dump = uut.resolve(tempDir.toFile(), null, optionsFor("src", false));

        assertThat(dump).contains("class Top {}");
        assertThat(dump).doesNotContain("class Deep {}");
    }

    @Test
    void testResolveWithScanSubsReadsNestedFiles() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src").resolve("Top.java"), "class Top {}");
        Files.createDirectories(tempDir.resolve("src").resolve("nested"));
        Files.writeString(tempDir.resolve("src").resolve("nested").resolve("Deep.java"), "class Deep {}");

        SourceDumpResolver uut = new SourceDumpResolver();
        String dump = uut.resolve(tempDir.toFile(), null, optionsFor("src", true));

        assertThat(dump).contains("class Top {}");
        assertThat(dump).contains("class Deep {}");
    }

    @Test
    void testResolveSkipsUnreadableFileWithoutFailing() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src").resolve("good.java"), "class Good {}");
        Path broken = tempDir.resolve("src").resolve("broken.java");
        Files.writeString(broken, "class Broken { SECRET }");
        Assumptions.assumeTrue(Files.getFileStore(broken).supportsFileAttributeView("posix"));
        // strip all permissions -> FileInputStream fails and the resolver must log & continue
        Files.setPosixFilePermissions(broken, EnumSet.noneOf(PosixFilePermission.class));

        SourceDumpResolver uut = new SourceDumpResolver();
        String dump = uut.resolve(tempDir.toFile(), null, optionsFor("src", true));

        assertThat(dump).contains("class Good {}");
    }
}