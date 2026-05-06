package de.spraener.prjxp.chuno.docs;

import de.spraener.prjxp.chuno.docs.model.DocArtifakt;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;

public class DocContentCollector {

    public static <T> T collectContent(DocArtifakt root, DocArtifaktType type) {
        StringBuilder sb = new StringBuilder();
        collectContent(root, type, sb);
        return (T) sb.toString();
    }

    private static <T> void collectContent(DocArtifakt artifakt, DocArtifaktType type, StringBuilder sb) {
        if( artifakt.getChilds() != null ) {
            for (var child : artifakt.getChilds()) {
                collectContent((DocArtifakt) child, type, sb);
            }
        }
        if( artifakt.getFormat().equals(type) ) {
            sb.append(artifakt.getData());
        }
    }
}
