package de.spraener.prjxp.docpipe.prompt;

import de.spraener.prjxp.docpipe.model.DPContentCreation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
