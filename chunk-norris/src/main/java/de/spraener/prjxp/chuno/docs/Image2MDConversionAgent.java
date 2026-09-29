package de.spraener.prjxp.chuno.docs;

import de.spraener.prjxp.common.chat.KIChatProvider;
import de.spraener.prjxp.chuno.docs.model.ConversionAccuracy;
import de.spraener.prjxp.chuno.docs.model.CostEstimation;
import de.spraener.prjxp.chuno.docs.model.DocArtifakt;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;

@Component
@RequiredArgsConstructor
public class Image2MDConversionAgent implements DocConversionAgent<BufferedImage, String> {
    private final KIChatProvider chatProvider;

    @Override
    public ConversionAccuracy accuracy() {
        return ConversionAccuracy.LOCAL_AI_DRIVEN;
    }

    @Override
    public DocArtifaktType getSourceFormat() {
        return DocArtifaktType.BUFFERED_IMAGE;
    }

    @Override
    public DocArtifaktType getTargetFormat() {
        return DocArtifaktType.MARK_DOWN;
    }

    @Override
    public double estimateCosts(DocArtifakt<BufferedImage, ?> artifakt) {
        return artifakt.getChildQuantityEstimation() * CostEstimation.AI_OCR_COSTS;
    }

    @Override
    public int estimateQuantity(DocArtifakt<BufferedImage, ?> artifakt) {
        return 1;
    }

    @Override
    public void convert(DocArtifakt<BufferedImage, ?> artifakt) {
        DocArtifakt converted = new DocArtifakt(artifakt)
                .setId(artifakt.getId()+".md")
                .setFormat(DocArtifaktType.MARK_DOWN)
                ;
        try {
            BufferedImage img = artifakt.getData();
            String result = chatProvider.getByStereotype("vision")
                    .map(chat -> chat.analyzeImage(img))
                    .orElse("Fehler: Kein Vision-Modell konfiguriert");
            converted.setData(result);
            artifakt.addChild(converted);
        } catch (Exception e) {
            converted.setData("Fehler bei der Bild-Konvertierung: " + e.getMessage());
        }
    }
}
