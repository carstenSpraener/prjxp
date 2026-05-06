package de.spraener.prjxp.chuno.docs.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Accessors(chain = true)
public class DocArtifakt<T,C> {
    private String id;
    private String parentId;
    private DocArtifaktType format;
    private T data;
    private List<DocArtifakt<C,?>> childs;
    private int childQuantityEstimation = 1;
    private Map<String, Object> estimationMetadata = new HashMap<>();

    private DocArtifakt(){}

    public static DocArtifakt<File, ?> createRoot(File f) {
        DocArtifakt root = new DocArtifakt();
        root.setData(f);
        root.setFormat(DocArtifaktType.fromFile(f));
        return root;
    }

    public DocArtifakt( DocArtifakt<?,?> parent ) {
        if( parent == null ) {
            throw new IllegalArgumentException( "Parent must not be null");

        }
        this.parentId = parent.getId();
    }

    public DocArtifakt<T,C> addChild(DocArtifakt<C,?> child) {
        if( childs == null ) {
            childs = new ArrayList<>();
        }
        this.childs.add(child);
        child.setParentId(this.getId());
        return this;
    }

    public DocArtifakt<T,C> replace(DocArtifakt<C,?> child, DocArtifakt<C,?> replacement) {
        int idx = this.childs.indexOf(child);
        if( idx >= 0 ) {
            this.childs.set(idx, replacement);
        }
        return this;
    }

}
