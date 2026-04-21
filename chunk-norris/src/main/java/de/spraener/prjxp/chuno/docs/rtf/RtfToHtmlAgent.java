package de.spraener.prjxp.chuno.docs.rtf;

import de.spraener.prjxp.chuno.docs.DocConversionAgent;
import de.spraener.prjxp.chuno.docs.model.CostEstimation;
import de.spraener.prjxp.chuno.docs.model.DocArtifakt;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.microsoft.rtf.RTFParser;
import org.apache.tika.sax.ToHTMLContentHandler;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

@Component
public class RtfToHtmlAgent implements DocConversionAgent<File, String> {

    @Override
    public void convert(DocArtifakt<File, ?> input) {
        try (InputStream stream = new FileInputStream(input.getData())) {
            // Tika Handler für HTML-Ausgabe
            ToHTMLContentHandler handler = new ToHTMLContentHandler();
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();

            // RTF Parser initialisieren
            RTFParser parser = new RTFParser();
            parser.parse(stream, handler, metadata, context);

            // Das Ergebnis ist valides XHTML
            String xhtml = handler.toString();

            DocArtifakt htmlArt = new DocArtifakt<>(input);
            htmlArt.setData(xhtml);
            htmlArt.setFormat(DocArtifaktType.HTML);

        } catch (Exception e) {
            throw new RuntimeException("Fehler bei RTF -> HTML Konvertierung", e);
        }
    }

    @Override
    public DocArtifaktType getSourceFormat() {
        return DocArtifaktType.RTF;
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
}