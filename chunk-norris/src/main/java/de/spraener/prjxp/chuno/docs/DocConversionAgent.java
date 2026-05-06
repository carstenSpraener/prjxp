package de.spraener.prjxp.chuno.docs;

import de.spraener.prjxp.chuno.docs.model.DocArtifakt;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;

public interface DocConversionAgent<T,C> {
    DocArtifaktType getSourceFormat();
    DocArtifaktType getTargetFormat();
    double estimateCosts(DocArtifakt<T,?> artifakt);
    int estimateQuantity(DocArtifakt<T,?> artifakt);
    void convert(DocArtifakt<T,?> artifakt);
}
