package de.spraener.prjxp.chuno.code.visualbasic;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxFileType;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class VisualBasicCodeChunkerTests {

    private final VisualBasicCodeChunker uut = new VisualBasicCodeChunker();

    @Test
    public void chunksVbNetClassAtMethodBoundaries() throws Exception {
        List<PxChunk> chunkList = chunkVisualBasicString("CustomerService", ".vb", """
                Imports System
                Imports System.Collections.Generic

                Public Class CustomerService
                    Private counter As Integer

                    ''' <summary>
                    ''' Loads a customer.
                    ''' </summary>
                    Public Function LoadCustomer(id As Integer) As String
                        Return id.ToString()
                    End Function

                    Private Sub ResetCounter()
                        counter = 0
                    End Sub
                End Class
                """);

        assertThat(chunkList)
                .isNotEmpty()
                .anyMatch(c -> c.getId().equals("CustomerService.imports") &&
                        c.getContent().contains("Imports System") &&
                        hasCodeSection(c, "imports"))
                .anyMatch(c -> c.getId().equals("CustomerService.CustomerService") &&
                        c.getContent().contains("Public Class CustomerService") &&
                        c.getContent().contains("Private counter As Integer") &&
                        c.getContent().contains("Public Function LoadCustomer(id As Integer) As String") &&
                        c.getContent().contains("Private Sub ResetCounter()") &&
                        !c.getContent().contains("Return id.ToString()") &&
                        hasCodeSection(c, "classFrame"))
                .anyMatch(c -> c.getId().equals("CustomerService.CustomerService.LoadCustomer") &&
                        c.getContent().contains("Public Function LoadCustomer(id As Integer) As String") &&
                        c.getContent().contains("Return id.ToString()") &&
                        c.getContent().endsWith("End Function\n") &&
                        hasCodeSection(c, "method"))
                .anyMatch(c -> c.getId().equals("CustomerService.CustomerService.LoadCustomer.doc") &&
                        c.getContent().contains("Loads a customer") &&
                        hasCodeSection(c, "methodDoc"))
                .anyMatch(c -> c.getId().equals("CustomerService.CustomerService.ResetCounter") &&
                        c.getContent().contains("Private Sub ResetCounter()") &&
                        c.getContent().contains("counter = 0") &&
                        c.getContent().endsWith("End Sub\n") &&
                        hasCodeSection(c, "method"));
    }

    @Test
    public void chunksVbaModuleAtProcedureBoundaries() throws Exception {
        List<PxChunk> chunkList = chunkVisualBasicString("LegacyModule", ".bas", """
                Attribute VB_Name = "LegacyModule"
                Option Explicit

                ' Greets the given user.
                Public Sub Greet(ByVal name As String)
                    MsgBox "Hello " & name
                End Sub

                Private Function BuildMessage(ByVal name As String) As String
                    BuildMessage = "Hello " & name
                End Function
                """);

        assertThat(chunkList)
                .isNotEmpty()
                .anyMatch(c -> c.getId().equals("LegacyModule.header") &&
                        c.getContent().contains("Attribute VB_Name") &&
                        c.getContent().contains("Option Explicit") &&
                        hasCodeSection(c, "imports"))
                .anyMatch(c -> c.getId().equals("LegacyModule.Greet") &&
                        c.getContent().contains("Public Sub Greet(ByVal name As String)") &&
                        c.getContent().contains("MsgBox \"Hello \" & name") &&
                        c.getContent().endsWith("End Sub\n") &&
                        hasCodeSection(c, "method"))
                .anyMatch(c -> c.getId().equals("LegacyModule.Greet.doc") &&
                        c.getContent().contains("Greets the given user") &&
                        hasCodeSection(c, "methodDoc"))
                .anyMatch(c -> c.getId().equals("LegacyModule.BuildMessage") &&
                        c.getContent().contains("Private Function BuildMessage(ByVal name As String) As String") &&
                        c.getContent().contains("BuildMessage = \"Hello \" & name") &&
                        c.getContent().endsWith("End Function\n") &&
                        hasCodeSection(c, "method"))
                .anyMatch(c -> c.getId().equals("LegacyModule") &&
                        c.getContent().contains("Public Sub Greet(ByVal name As String)") &&
                        c.getContent().contains("Private Function BuildMessage(ByVal name As String) As String") &&
                        !c.getContent().contains("MsgBox \"Hello \" & name") &&
                        hasCodeSection(c, "classFrame"));
    }

    @Test
    public void matchesVisualBasicFileExtensions() {
        assertThat(PxFileType.VISUAL_BASIC_CODE.matches(new File("Example.vb"))).isTrue();
        assertThat(PxFileType.VISUAL_BASIC_CODE.matches(new File("Example.bas"))).isTrue();
        assertThat(PxFileType.VISUAL_BASIC_CODE.matches(new File("Example.cls"))).isTrue();
        assertThat(PxFileType.VISUAL_BASIC_CODE.matches(new File("Example.frm"))).isTrue();
    }

    @Test
    public void chunksWindows1252EncodedVisualBasicFiles() throws Exception {
        File tmpFileDir = new File("src/test/tmp");
        tmpFileDir.mkdirs();
        File tmp = File.createTempFile("Windows1252Module", ".vb", tmpFileDir);
        Files.writeString(tmp.toPath(), """
                Attribute VB_Name = "Windows1252Module"
                Option Explicit

                Public Function Greeting() As String
                    Greeting = "Überblick"
                End Function
                """, Charset.forName("windows-1252"));
        tmp.deleteOnExit();

        List<PxChunk> chunkList = uut.chunk(tmp).toList();

        assertThat(chunkList)
                .isNotEmpty()
                .anyMatch(c -> c.getId().equals("Windows1252Module.Greeting") &&
                        c.getContent().contains("Greeting = \"Überblick\"") &&
                        hasCodeSection(c, "method"));
    }

    @Test
    public void keepsUnicodeIdentifierNamesAndCreatesUniqueIdsForOverloads() throws Exception {
        List<PxChunk> chunkList = chunkVisualBasicString("BestellerFunctions", ".vb", """
                Public Module BestellerFunctions
                    Public Function SetBestellerAbkürzelZulässig(bestellerKürzelText As String, comboBestellerAbkürzel As ComboBox) As Boolean
                        Return True
                    End Function

                    ' overload for user combo box
                    Public Function SetBestellerAbkürzelZulässig(bestellerKürzelText As String, comboBestellerAbkürzel As UserComboBox) As Boolean
                        Return True
                    End Function

                    Public Function IsBestellerNameTextZulässigReturnBestellerK(bestellerNameText As String) As String
                        Return String.Empty
                    End Function
                End Module
                """);

        List<String> methodIds = chunkList.stream()
                .filter(c -> hasCodeSection(c, "method"))
                .map(PxChunk::getId)
                .toList();

        assertThat(methodIds)
                .contains("BestellerFunctions.BestellerFunctions.SetBestellerAbkürzelZulässig.overload1")
                .contains("BestellerFunctions.BestellerFunctions.SetBestellerAbkürzelZulässig.overload2")
                .contains("BestellerFunctions.BestellerFunctions.IsBestellerNameTextZulässigReturnBestellerK")
                .doesNotHaveDuplicates();

        assertThat(chunkList)
                .anyMatch(c -> c.getId().equals("BestellerFunctions.BestellerFunctions.SetBestellerAbkürzelZulässig.overload2.doc") &&
                        c.getParent().equals("BestellerFunctions.BestellerFunctions.SetBestellerAbkürzelZulässig.overload2") &&
                        c.getContent().contains("overload for user combo box"));
    }

    @Test
    public void chunksConfiguredRealWorldVisualBasicFile() {
        String realFile = System.getProperty("visualbasic.realFile");
        assumeTrue(realFile != null && Files.exists(Path.of(realFile)));

        File sourceFile = new File(realFile);
        List<PxChunk> chunkList = uut.chunk(sourceFile).toList();

        assertThat(chunkList).isNotEmpty();
        if (sourceFile.getName().equals("BestellerFunctions.vb")) {
            List<String> methodIds = chunkList.stream()
                    .filter(c -> hasCodeSection(c, "method"))
                    .map(PxChunk::getId)
                    .toList();

            assertThat(methodIds)
                    .contains("BestellerFunctions.BestellerFunctions.SetBestellerAbkürzelZulässig.overload1")
                    .contains("BestellerFunctions.BestellerFunctions.SetBestellerAbkürzelZulässig.overload2")
                    .contains("BestellerFunctions.BestellerFunctions.SetBestellerTextZulässig.overload1")
                    .contains("BestellerFunctions.BestellerFunctions.SetBestellerTextZulässig.overload2")
                    .contains("BestellerFunctions.BestellerFunctions.SetBestellerTextZulässig.overload3")
                    .contains("BestellerFunctions.BestellerFunctions.IsBestellerNameTextZulässig")
                    .contains("BestellerFunctions.BestellerFunctions.IsBestellerNameTextZulässigReturnBestellerK")
                    .doesNotContain("BestellerFunctions.BestellerFunctions.SetBestellerAbk")
                    .doesNotContain("BestellerFunctions.BestellerFunctions.SetBestellerTextZul")
                    .doesNotContain("BestellerFunctions.BestellerFunctions.IsBestellerNameTextZul")
                    .doesNotHaveDuplicates();
        }
    }

    private boolean hasCodeSection(PxChunk c, String codeSectionName) {
        return codeSectionName.equals(c.getMetadata().get(VisualBasicCodeChunker.MDKEY_CODESECTION));
    }

    private List<PxChunk> chunkVisualBasicString(String name, String extension, String code) throws Exception {
        File tmpFileDir = new File("src/test/tmp");
        tmpFileDir.mkdirs();
        File tmp = File.createTempFile(name, extension, tmpFileDir);
        try (FileWriter writer = new FileWriter(tmp, StandardCharsets.UTF_8)) {
            IOUtils.write(code, writer);
        }
        tmp.deleteOnExit();
        return uut.chunk(tmp).toList();
    }
}
