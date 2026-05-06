package de.spraener.prjxp.chuno.docs.pdf;

import de.spraener.prjxp.chuno.docs.DocConversionAgent;
import de.spraener.prjxp.chuno.docs.model.CostEstimation;
import de.spraener.prjxp.chuno.docs.model.DocArtifakt;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;
import lombok.extern.java.Log;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

@Component
@Log
public class Pdf2MDWithTikaDocConversionAgent implements DocConversionAgent<File,String> {
    @Override
    public DocArtifaktType getSourceFormat() {
        return DocArtifaktType.PDF;
    }

    @Override
    public DocArtifaktType getTargetFormat() {
        return DocArtifaktType.MARK_DOWN;
    }

    @Override
    public double estimateCosts(DocArtifakt<File, ?> artifakt) {
        if (Pdf2TextConversionAgent.isPdfWithText(artifakt.getData())) {
            return CostEstimation.SIMPLE - 1;
        } else {
            return CostEstimation.IMPOSSIBLE;
        }
    }

    @Override
    public int estimateQuantity(DocArtifakt<File, ?> artifakt) {
        return 1;
    }

    @Override
    public void convert(DocArtifakt<File, ?> artifakt) {
        try {
            InputStream stream = new FileInputStream(artifakt.getData());

            // BodyContentHandler mit Limit -1 (unbegrenzt)
            BodyContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            PDFParser parser = new PDFParser();

            parser.parse(stream, handler, metadata, new ParseContext());

            // Der Handler liefert bereits grob strukturierten Text
            String plainText = handler.toString();

            // Manuelle Nachbearbeitung für Markdown
            String markdown = postProcess(plainText);
            DocArtifakt result = new DocArtifakt<>(artifakt)
                    .setData(markdown)
                    .setId(artifakt.getId()+".md")
                    .setFormat(DocArtifaktType.MARK_DOWN)
                    ;
            artifakt.addChild(result);
        } catch( Exception e ) {
            log.severe("Error in conversion of PDF "+artifakt.getData().getName()+": "+e.getMessage());
        }
    }

    private static String postProcess(String text) {
        // Beispiel: Mehrfache Zeilenumbrüche normalisieren
        return text.replaceAll("(\\r?\\n){3,}", "\n\n")
                .trim();
    }
}