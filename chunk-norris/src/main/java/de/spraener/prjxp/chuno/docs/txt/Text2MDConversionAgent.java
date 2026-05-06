package de.spraener.prjxp.chuno.docs.txt;

import de.spraener.prjxp.chuno.docs.DocConversionAgent;
import de.spraener.prjxp.chuno.docs.model.CostEstimation;
import de.spraener.prjxp.chuno.docs.model.DocArtifakt;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;
import org.springframework.stereotype.Component;

@Component
public class Text2MDConversionAgent implements DocConversionAgent<String,String> {
    @Override
    public DocArtifaktType getSourceFormat() {
        return DocArtifaktType.TEXT;
    }

    @Override
    public DocArtifaktType getTargetFormat() {
        return DocArtifaktType.MARK_DOWN;
    }

    @Override
    public double estimateCosts(DocArtifakt<String, ?> artifakt) {
        return CostEstimation.ATOMAR;
    }

    @Override
    public int estimateQuantity(DocArtifakt<String, ?> artifakt) {
        return 1;
    }

    @Override
    public void convert(DocArtifakt<String, ?> artifakt) {
        DocArtifakt converted =  new DocArtifakt(artifakt)
                .setData(artifakt.getData())
                .setFormat(DocArtifaktType.MARK_DOWN)
                .setId(artifakt.getId()+".md")
                ;
        artifakt.addChild(converted);
    }
}
