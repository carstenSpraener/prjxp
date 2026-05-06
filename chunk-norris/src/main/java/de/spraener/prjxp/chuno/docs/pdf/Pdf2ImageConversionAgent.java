package de.spraener.prjxp.chuno.docs.pdf;

import de.spraener.prjxp.chuno.docs.DocConversionAgent;
import de.spraener.prjxp.chuno.docs.model.CostEstimation;
import de.spraener.prjxp.chuno.docs.model.DocArtifakt;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;
import lombok.extern.java.Log;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

@Component
@Log
public class Pdf2ImageConversionAgent implements DocConversionAgent<File, BufferedImage> {
    @Override
    public DocArtifaktType getSourceFormat() {
        return DocArtifaktType.PDF;
    }

    @Override
    public DocArtifaktType getTargetFormat() {
        return DocArtifaktType.BUFFERED_IMAGE;
    }

    @Override
    public double estimateCosts(DocArtifakt<File, ?> artifakt) {
        return  CostEstimation.IMAGE_OF_PAGE_COSTS;
    }

    @Override
    public int estimateQuantity(DocArtifakt<File, ?> artifakt) {
        int pageCount = pageCount(artifakt.getData());
        artifakt.setChildQuantityEstimation(pageCount);
        return pageCount;
    }

    private int pageCount(Object data) {
        try (PDDocument document = Loader.loadPDF((File) data)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            return 0;
        }
    }

    public void convert(DocArtifakt<File, ?> artifakt) {
        try (PDDocument document = Loader.loadPDF(artifakt.getData())) {
            PDFRenderer pdfRenderer = new PDFRenderer(document);

            for (int page = 0; page < Math.min(10, document.getNumberOfPages()); ++page) {
                BufferedImage bim = pdfRenderer.renderImageWithDPI(page, 300, ImageType.BINARY);
                log.info(getClass().getSimpleName()+": Renderd page "+page);
                artifakt.addChild(new DocArtifakt(artifakt)
                        .setData(bim)
                        .setFormat(DocArtifaktType.BUFFERED_IMAGE)
                        .setId(artifakt.getId() + "." + page)
                );
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
