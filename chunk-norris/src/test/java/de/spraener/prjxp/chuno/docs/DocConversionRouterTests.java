package de.spraener.prjxp.chuno.docs;

import de.spraener.prjxp.chuno.docs.model.DocArtifakt;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;
import de.spraener.prjxp.chuno.docs.pdf.Pdf2ImageConversionAgent;
import de.spraener.prjxp.chuno.docs.pdf.Pdf2MDWithTikaDocConversionAgent;
import de.spraener.prjxp.chuno.docs.pdf.Pdf2TextConversionAgent;
import de.spraener.prjxp.chuno.docs.txt.Text2MDConversionAgent;
import de.spraener.prjxp.common.config.PrjXPConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

public class DocConversionRouterTests {

    @Test
    public void runConversionOfPDF() throws Exception {
        String path = System.getenv("PDF_TEST_DOC");
        File f = new File(path);
        PrjXPConfig config = new PrjXPConfig();
        config.setEmbeddingOllamaUrl("http://localhost:11434");
        List<DocConversionAgent> agents = List.of(
                new Pdf2TextConversionAgent(),
                new Pdf2ImageConversionAgent(),
                new Pdf2MDWithTikaDocConversionAgent(),
                new Image2MDConversionAgent(config),
                new Text2MDConversionAgent()
        );
        DocConversionRouter uut = new DocConversionRouter(agents);
        Object data = uut.runConversion(f, DocArtifaktType.PDF, DocArtifaktType.MARK_DOWN);
        System.out.println(data);
    }
}
