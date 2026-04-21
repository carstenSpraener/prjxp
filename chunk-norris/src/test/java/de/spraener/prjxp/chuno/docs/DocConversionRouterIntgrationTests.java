package de.spraener.prjxp.chuno.docs;

import de.spraener.prjxp.chuno.docs.model.ConversionAccuracy;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;
import de.spraener.prjxp.common.util.SpringContextSupplier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.ActiveProfiles;

import java.awt.image.BufferedImage;
import java.io.File;

@SpringBootTest
@ComponentScan("de.spraener.prjxp")
@ActiveProfiles("test")
public class DocConversionRouterIntgrationTests {
    @Autowired
    DocConversionRouter uut;

    @Autowired
    SpringContextSupplier contextSupplier;

    @Test
    public void runConversionOfPDF() throws Exception {
        String path = System.getenv("PDF_TEST_DOC");
        Assumptions.assumeTrue(path != null && !path.isEmpty(), "No test pdf given in environment variable PDF_TEST_DOC");

        File f = new File(path);

        // run the conversion...
        Object data = uut.doConversion(
                f, // ... of a file f
                DocArtifaktType.PDF, // which has format PDF
                DocArtifaktType.MARK_DOWN, // to a format MARK_DOWN
                ConversionAccuracy.INFORMATION_LOST // with information lost is accepted
        );
        System.out.println(data);
    }

    @Test
    public void runConversionOfPDFToImageListNoOOMException() throws Exception {
        String path = System.getenv("PDF_TEST_DOC");
        Assumptions.assumeTrue(path != null && !path.isEmpty(), "No test pdf given in environment variable PDF_TEST_DOC");

        File f = new File(path);
        // run the conversion...
        uut.doConversion(
                f, // ... of a file f
                DocArtifaktType.PDF, // which has format PDF
                DocArtifaktType.BUFFERED_IMAGE, // to a format MARK_DOWN
                ConversionAccuracy.INFORMATION_LOST, // with information lost is accepted
                // and collect all converted artifakts
                da -> {
                    BufferedImage pageImg = (BufferedImage) da.getData();
                    System.out.println(da.getId() + ": " + pageImg.getWidth() + "x" + pageImg.getHeight());
                }
        );
    }

    @Test
    public void runConversionWord2Html() throws Exception {
        File f = getResourceAsFile("/WordTestDoc.docx");
        String text = uut.doConversion(
                f,
                DocArtifaktType.WORD_DOC,
                DocArtifaktType.MARK_DOWN,
                ConversionAccuracy.ANALYTIC
        );

        System.out.println(text);
    }

    private File getResourceAsFile(String rsrcPath) {
        return new File(getClass().getResource(rsrcPath).getFile());
    }
}
