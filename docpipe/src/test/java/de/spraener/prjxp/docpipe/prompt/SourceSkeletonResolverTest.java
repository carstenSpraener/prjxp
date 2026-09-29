package de.spraener.prjxp.docpipe.prompt;

import de.spraener.prjxp.docpipe.model.DPContentCreation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceSkeletonResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void sourceDumpIsAvailableViaNewIdAndLegacyAlias() throws Exception {
        Path srcDir = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(srcDir.resolve("Hello.java"), """
                package demo;

                public class Hello {
                    public String greet() {
                        return "Hello World";
                    }
                }
                """, StandardCharsets.UTF_8);

        SourceDumpResolver sourceDumpResolver = new SourceDumpResolver();
        PromptResolvingService service = new PromptResolvingService(List.of(sourceDumpResolver), null);
        DPContentCreation dpcc = new DPContentCreation();

        String resolved = service.resolve(dpcc, "{{src-dump \"src\" ending=\"java\"}}\n---\n{{java-src-dump \"src\" ending=\"java\"}}", tempDir.toFile());

        assertThat(sourceDumpResolver.getID()).isEqualTo("src-dump");
        assertThat(resolved)
                .contains("public class Hello")
                .contains("Hello World");
        assertThat(resolved.indexOf("public class Hello"))
                .isNotEqualTo(resolved.lastIndexOf("public class Hello"));
    }

    @Test
    void sourceSkeletonCreatesVisualBasicSkeletonWithoutImplementationDetails() throws Exception {
        Path srcDir = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(srcDir.resolve("CustomerService.vb"), """
                Imports System

                Public Class CustomerService
                    Private counter As Integer

                    ''' <summary>
                    ''' Loads a customer.
                    ''' </summary>
                    Public Function LoadCustomer(id As Integer) As String
                        Dim secretSql = "SELECT * FROM CUSTOMER"
                        Return id.ToString()
                    End Function

                    Private Sub ResetCounter()
                        counter = 0
                    End Sub
                End Class
                """, StandardCharsets.UTF_8);

        PromptResolvingService service = new PromptResolvingService(
                List.of(new SourceSkeletonResolver(List.of(new VisualBasicSourceSkeletonizer()))),
                null
        );
        DPContentCreation dpcc = new DPContentCreation();

        String resolved = service.resolve(dpcc, "{{src-skeleton \"src\" ending=\"vb\"}}", tempDir.toFile());

        assertThat(resolved)
                .contains("```vb")
                .contains("Imports System")
                .contains("Public Class CustomerService")
                .contains("Private counter As Integer")
                .contains("Public Function LoadCustomer(id As Integer) As String")
                .contains("Private Sub ResetCounter()")
                .contains("End Function")
                .contains("End Sub")
                .contains("End Class")
                .doesNotContain("SELECT * FROM CUSTOMER")
                .doesNotContain("Return id.ToString()")
                .doesNotContain("counter = 0");
    }

    @Test
    void sourceSkeletonFallsBackToFullDumpWhenNoSkeletonizerMatches() throws Exception {
        Path srcDir = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(srcDir.resolve("notes.txt"), """
                Architecture note
                Keep this content when no skeletonizer exists.
                """, StandardCharsets.UTF_8);

        PromptResolvingService service = new PromptResolvingService(
                List.of(new SourceSkeletonResolver(List.of(new VisualBasicSourceSkeletonizer()))),
                null
        );
        DPContentCreation dpcc = new DPContentCreation();

        String resolved = service.resolve(dpcc, "{{src-skeleton \"src\" ending=\"txt\"}}", tempDir.toFile());

        assertThat(resolved)
                .contains("<!-- no skeletonizer for")
                .contains("notes.txt")
                .contains("```txt")
                .contains("Keep this content when no skeletonizer exists.");
    }

    @Test
    void sourceSkeletonFallsBackToFullDumpWhenSkeletonizerThrows() throws Exception {
        Path srcDir = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(srcDir.resolve("CustomerService.vb"), """
                Public Class CustomerService
                    Private counter As Integer
                End Class
                """, StandardCharsets.UTF_8);

        SourceSkeletonizer failing = mock(SourceSkeletonizer.class);
        when(failing.supports(any(File.class), anyString())).thenReturn(true);
        when(failing.skeletonize(any(File.class))).thenThrow(new RuntimeException("skeletonizer exploded"));

        PromptResolvingService service = new PromptResolvingService(
                List.of(new SourceSkeletonResolver(List.of(failing))),
                null
        );
        DPContentCreation dpcc = new DPContentCreation();

        String resolved = service.resolve(dpcc, "{{src-skeleton \"src\" ending=\"vb\"}}", tempDir.toFile());

        assertThat(resolved)
                .contains("<!-- skeletonizer failed for")
                .contains("CustomerService.vb")
                .contains("```vb")
                .contains("Private counter As Integer");
    }

    @Test
    void sourceSkeletonFallbackDumpSurvivesUnreadableFile() throws Exception {
        Path srcDir = Files.createDirectories(tempDir.resolve("src"));
        Path unreadable = srcDir.resolve("locked.txt");
        Files.writeString(unreadable, "TOP SECRET CONTENT");
        // strip all permissions -> the fallback dump's FileInputStream fails and must be logged, not thrown
        Files.setPosixFilePermissions(unreadable, EnumSet.noneOf(PosixFilePermission.class));

        PromptResolvingService service = new PromptResolvingService(
                List.of(new SourceSkeletonResolver(List.of(new VisualBasicSourceSkeletonizer()))),
                null
        );
        DPContentCreation dpcc = new DPContentCreation();

        String resolved = service.resolve(dpcc, "{{src-skeleton \"src\" ending=\"txt\"}}", tempDir.toFile());

        assertThat(resolved)
                .contains("<!-- no skeletonizer for")
                .doesNotContain("TOP SECRET CONTENT");
    }

    @Test
    void sourceSkeletonUsesFirstExplicitParamWhenTwoParamsGiven() throws Exception {
        // With two params, Handlebars passes the first as context and the second via options.param(0);
        // firstParamOrContext must prefer the explicit param over the context.
        Path srcDir = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(srcDir.resolve("notes.txt"), "param-wins");

        PromptResolvingService service = new PromptResolvingService(
                List.of(new SourceSkeletonResolver(List.of())),
                null
        );
        DPContentCreation dpcc = new DPContentCreation();

        String resolved = service.resolve(dpcc, "{{src-skeleton \"ignored-context\" \"src\" ending=\"txt\"}}", tempDir.toFile());

        assertThat(resolved).contains("param-wins");
    }

    @Test
    void sourceSkeletonWithoutScanSubsOnlyReadsTopLevel() throws Exception {
        Path srcDir = Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(srcDir.resolve("top.txt"), "top-level");
        Files.createDirectories(srcDir.resolve("nested"));
        Files.writeString(srcDir.resolve("nested").resolve("deep.txt"), "deep-level");

        PromptResolvingService service = new PromptResolvingService(
                List.of(new SourceSkeletonResolver(List.of())),
                null
        );
        DPContentCreation dpcc = new DPContentCreation();

        String resolved = service.resolve(dpcc, "{{src-skeleton \"src\" ending=\"txt\" scanSubs=false}}", tempDir.toFile());

        assertThat(resolved).contains("top-level");
        assertThat(resolved).doesNotContain("deep-level");
    }
}
