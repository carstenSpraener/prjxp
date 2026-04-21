package de.spraener.prjxp.chuno.docs.pdf;

import de.spraener.prjxp.chuno.docs.DocConversionAgent;
import de.spraener.prjxp.chuno.docs.model.CostEstimation;
import de.spraener.prjxp.chuno.docs.model.DocArtifakt;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;
import lombok.extern.java.Log;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

@Component
@Log
public class Pdf2TextConversionAgent implements DocConversionAgent<File,String> {

    @Override
    public DocArtifaktType getSourceFormat() {
        return DocArtifaktType.PDF;
    }

    @Override
    public DocArtifaktType getTargetFormat() {
        return DocArtifaktType.TEXT;
    }

    @Override
    public double estimateCosts(DocArtifakt<File, ?> artifakt) {
        if( isPdfWithText(artifakt.getData()) ) {
            return CostEstimation.SIMPLE;
        } else {
            return CostEstimation.IMPOSSIBLE;
        }
    }

    static boolean isPdfWithText(File pdf) {
        try (PDDocument document = Loader.loadPDF(pdf) ) {
            PDFTextStripper stripper = new PDFTextStripper();

            // Optional: Nur die erste Seite prüfen, um Zeit zu sparen
            stripper.setStartPage(1);
            stripper.setEndPage(1);

            String text = stripper.getText(document);

            if (text.trim().isEmpty()) {
                log.info("Das PDF '"+pdf.getName()+"' enthält keinen extrahierbaren Text (wahrscheinlich ein Scan).");
                return false;
            } else {
                log.fine("Textextraktion ist für Dokument '"+pdf.getName()+"' möglich.");
                return true;
            }
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public int estimateQuantity(DocArtifakt<File, ?> artifakt) {
        return 1;
    }

    @Override
    public void convert(DocArtifakt<File, ?> artifakt) {
        DocArtifakt textArtifact = new DocArtifakt<>(artifakt)
                .setFormat(DocArtifaktType.TEXT)
                .setId(artifakt.getId()+".txt")
                .setParentId(artifakt.getParentId())
                ;
        try (PDDocument document = Loader.loadPDF(artifakt.getData())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            textArtifact.setData(text);
            artifakt.addChild(textArtifact);
        } catch(IOException e ) {
            log.warning("Error while extracting text for document "+artifakt.getData().getName());
        }
    }

}
