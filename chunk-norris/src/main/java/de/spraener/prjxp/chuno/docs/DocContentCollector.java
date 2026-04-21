package de.spraener.prjxp.chuno.docs;

import de.spraener.prjxp.chuno.docs.model.DocArtifakt;
import de.spraener.prjxp.chuno.docs.model.DocArtifaktType;

import javax.print.Doc;
import java.util.function.Consumer;

public class DocContentCollector {

    public static String collectTextContent(DocArtifakt root, DocArtifaktType type) {
        StringBuilder sb = new StringBuilder();
        collectContent(root, type, da -> sb.append(da.getData()));
        return sb.toString();
    }

    public static void collectContent(DocArtifakt artifakt, DocArtifaktType type, Consumer<DocArtifakt> consumer) {
        if( artifakt.getChilds() != null ) {
            for (var child : artifakt.getChilds()) {
                collectContent((DocArtifakt) child, type, consumer);
            }
        }
        if( artifakt.getFormat().equals(type) ) {
            consumer.accept(artifakt);
        }
        artifakt.getPostConversionAction().accept(artifakt);
    }
}
