package de.spraener.prjxp.chuno.docs.poi;

import de.spraener.prjxp.chuno.docs.DocConversionAgent;
import de.spraener.prjxp.chuno.docs.model.CostEstimation;
import de.spraener.prjxp.chuno.docs.model.DocArtifakt;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;
import fr.opensagres.poi.xwpf.converter.xhtml.XHTMLConverter;
import fr.opensagres.poi.xwpf.converter.xhtml.XHTMLOptions;
import lombok.extern.java.Log;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Component
@Log
public class Docx2HtmlConversionAgent implements DocConversionAgent<File, String> {
    @Override
    public DocArtifaktType getSourceFormat() {
        return DocArtifaktType.WORD_DOCX;
    }

    @Override
    public DocArtifaktType getTargetFormat() {
        return DocArtifaktType.HTML;
    }

    @Override
    public double estimateCosts(DocArtifakt<File, ?> artifakt) {
        return CostEstimation.SIMPLE;
    }

    @Override
    public int estimateQuantity(DocArtifakt<File, ?> artifakt) {
        return 1;
    }

    @Override
    public void convert(DocArtifakt<File, ?> input) {
        log.fine("Converting file "+input.getData().getAbsolutePath()+" to HTML");
        try (InputStream in = new FileInputStream(input.getData());
             XWPFDocument document = new XWPFDocument(in)) {

            XHTMLOptions options = XHTMLOptions.create();
            options.setFragment(true);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XHTMLConverter.getInstance().convert(document, out, options);

            DocArtifakt htmlArt = new DocArtifakt<>(input)
                    .setData(out.toString(StandardCharsets.UTF_8))
                    .setFormat(DocArtifaktType.HTML)
                    .setId(input.getId() + ".html")
            ;

            input.addChild(htmlArt);
        } catch (Exception e) {
            throw new RuntimeException("Fehler bei DOCX-HTML Konvertierung", e);
        }
    }
}
