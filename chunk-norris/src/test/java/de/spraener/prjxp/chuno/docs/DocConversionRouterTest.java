package de.spraener.prjxp.chuno.docs;

import de.spraener.prjxp.chuno.docs.config.ConversionRoutesConfig;
import de.spraener.prjxp.chuno.docs.html.Html2MDConversionAgent;
import de.spraener.prjxp.chuno.docs.model.ConversionAccuracy;
import de.spraener.prjxp.chuno.docs.model.CostEstimation;
import de.spraener.prjxp.chuno.docs.model.DocArtifakt;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;
import de.spraener.prjxp.chuno.docs.txt.Text2MDConversionAgent;
import de.spraener.prjxp.common.util.BeanNameFinder;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class DocConversionRouterTest {
    private DocConversionRouter uut;
    private List<DocConversionAgent> agents = new ArrayList<>();
    private BeanNameFinder beanNameFinder = mock(BeanNameFinder.class);
    private ConversionRoutesConfig conversionRoutesConfig = mock(ConversionRoutesConfig.class);
    private DocConversionAgent agentPDF2HTML = mockAgent(DocArtifaktType.PDF, DocArtifaktType.HTML, 1.0, 1);
    private DocConversionAgent agentHTML2MD = mockAgent(DocArtifaktType.HTML, DocArtifaktType.MARK_DOWN, 1.0, 2);

    @BeforeEach
    public void setup() {
        uut = new DocConversionRouter(agents, beanNameFinder, conversionRoutesConfig);

    }

    private DocConversionAgent mockAgent(DocArtifaktType from, DocArtifaktType to, double costs, int quantity) {
        DocConversionAgent agent = mock(DocConversionAgent.class);
        when(agent.getSourceFormat()).thenReturn(from);
        when(agent.getTargetFormat()).thenReturn(to);
        when(agent.estimateCosts(any())).thenReturn(costs);
        when(agent.estimateQuantity(any())).thenReturn(quantity);
        when(agent.accuracy()).thenReturn(ConversionAccuracy.ANALYTIC);

        doAnswer(invocation -> {
            DocArtifakt da = invocation.getArgument(0);
            DocArtifakt child = new DocArtifakt(da);
            child.setId(from.name()+"->"+to.name());
            child.setFormat(to);
            da.addChild(child);
            return null;
        }).when(agent).convert(any(DocArtifakt.class));
        return agent;
    }

    @Test
    public void testSimpleRouting() throws Exception {
        agents.add(agentPDF2HTML);
        agents.add(agentHTML2MD);

        File fMock = mock(File.class);
        when(fMock.getAbsolutePath()).thenReturn("mockedFilePath/test.pdf");
        when(fMock.getName()).thenReturn("test.pdf");

        uut.doConversion(fMock, DocArtifaktType.PDF, DocArtifaktType.HTML, ConversionAccuracy.ANALYTIC);

        verify(agentPDF2HTML,times(1)).convert(argThat(da -> {
            return da.getId().equals("mockedFilePath/test.pdf") &&
                    da.getFormat().equals(DocArtifaktType.PDF)
                    ;
        }));
        verify(agentHTML2MD, never()).convert(any());
    }

    @Test
    public void testPDF2MDRouting() throws Exception {
        agents.add(agentPDF2HTML);
        agents.add(agentHTML2MD);

        File fMock = mock(File.class);
        when(fMock.getAbsolutePath()).thenReturn("mockedFilePath/test.pdf");
        when(fMock.getName()).thenReturn("test.pdf");

        uut.doConversion(fMock, DocArtifaktType.PDF, DocArtifaktType.MARK_DOWN, ConversionAccuracy.ANALYTIC);

        verify(agentPDF2HTML,times(1)).convert(argThat(da -> {
            return da.getId().equals("mockedFilePath/test.pdf") &&
                    da.getFormat().equals(DocArtifaktType.PDF)
                    ;
        }));
        verify(agentHTML2MD,times(1)).convert(argThat(da -> {
            return da.getId().equals("PDF->HTML") &&
                    da.getFormat().equals(DocArtifaktType.HTML)
                    ;
        }));
    }

    // --- Regression tests: real agents + real files (root artifakt holds a File) ---

    @Test
    public void testHtmlFileConversionWithRealAgent() throws Exception {
        agents.add(new Html2MDConversionAgent());

        File f = File.createTempFile("prjxp-test", ".html");
        f.deleteOnExit();
        Files.writeString(f.toPath(), "<html><body><h1>Hello</h1><p>World content</p></body></html>");

        String md = uut.doConversion(f, DocArtifaktType.HTML, DocArtifaktType.MARK_DOWN, ConversionAccuracy.ANALYTIC);

        Assertions.assertThat(md)
                .contains("Hello")
                .contains("World content");
    }

    @Test
    public void testTxtFileConversionWithRealAgent() throws Exception {
        agents.add(new Text2MDConversionAgent());

        File f = File.createTempFile("prjxp-test", ".txt");
        f.deleteOnExit();
        Files.writeString(f.toPath(), "Some plain text content");

        String md = uut.doConversion(f, DocArtifaktType.TEXT, DocArtifaktType.MARK_DOWN, ConversionAccuracy.ANALYTIC);

        Assertions.assertThat(md)
                .contains("Some plain text content");
    }

    @Test
    public void testMdFileNeedsNoConversion() throws Exception {
        // no agents registered: MARK_DOWN -> MARK_DOWN must yield the file content, not the file path
        File f = File.createTempFile("prjxp-test", ".md");
        f.deleteOnExit();
        Files.writeString(f.toPath(), "# Title\n\nSome markdown content");

        String md = uut.doConversion(f, DocArtifaktType.MARK_DOWN, DocArtifaktType.MARK_DOWN, ConversionAccuracy.ANALYTIC);

        Assertions.assertThat(md)
                .contains("Some markdown content");
    }
}
