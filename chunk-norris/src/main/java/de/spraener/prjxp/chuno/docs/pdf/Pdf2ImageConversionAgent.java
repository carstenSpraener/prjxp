package de.spraener.prjxp.chuno.docs.pdf;

import de.spraener.prjxp.chuno.docs.DocConversionAgent;
import de.spraener.prjxp.chuno.docs.model.CostEstimation;
import de.spraener.prjxp.chuno.docs.model.DocArtifakt;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.print.Doc;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;

@Component
@Log
public class Pdf2ImageConversionAgent implements DocConversionAgent<File, Supplier<BufferedImage>> {
    @Data
    class Pdf2ImageContext implements AutoCloseable {
        File pdf;
        PDDocument document;
        PDFRenderer renderer;

        Pdf2ImageContext(File pdf) {
            try {
                this.pdf = pdf;
                this.document = Loader.loadPDF(pdf);
                this.renderer = new PDFRenderer(document);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load PDF document: " + pdf.getAbsolutePath(), e);
            }
        }

        @Override
        public void close() {
            try {
                log.info("Closing PDF document " + pdf.getAbsolutePath());
                document.close();
            } catch (IOException e) {
                log.warning("Failed to close PDF document: " + document.getDocumentId() + ". Error: " + e.getMessage());
            }
        }
    }

    @RequiredArgsConstructor
    class ImageSupplier implements Supplier<BufferedImage> {
        private final Pdf2ImageContext pdfContext;
        private final int pageNumber;

        public BufferedImage get() {
            try {
                log.info(getClass().getSimpleName() + ": Renderd page " + pageNumber);
                return pdfContext.renderer.renderImageWithDPI(pageNumber, 300, ImageType.BINARY);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

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
        return CostEstimation.IMAGE_OF_PAGE_COSTS;
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
        Pdf2ImageContext context = new Pdf2ImageContext(artifakt.getData());
        DocArtifakt lastArtifakt = null;
        for (int page = 0; page < context.document.getNumberOfPages(); ++page) {
            lastArtifakt =
                    new DocArtifakt(artifakt) {
                        public BufferedImage getData() {
                            return ((ImageSupplier) super.getData()).get();
                        }
                    }
                    .setData(new ImageSupplier(context, page))
                    .setFormat(DocArtifaktType.BUFFERED_IMAGE)
                    .setId(artifakt.getId() + ".Image[" + page+"]");
            artifakt.addChild(lastArtifakt);
        }

        artifakt.setPostConversionAction((da)->{
                context.close();
        });
    }
}
