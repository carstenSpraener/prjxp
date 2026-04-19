package de.spraener.prjxp.gldrtrvr.chunks;

import de.spraener.prjxp.common.code.java.JavaCodeSection;
import de.spraener.prjxp.common.model.PxChunk;
import lombok.Data;
import lombok.ToString;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

@Data
public class ChunkNode {
    private Function<String, PxChunk> chunkReader;
    @ToString.Exclude
    private ChunkNode parent;
    private String type;
    private String chunkID;
    private int rootRank = 0; // only maintained for root nodes (parent == null)
    private Set<String> childIDs = new HashSet<>();
    private List<ChunkNode> childs = new ArrayList<>();

    public ChunkNode(String chunkId, String type, Function<String, PxChunk> chunkReader) {
        this.type = type;
        this.chunkID = chunkId;
        this.chunkReader = chunkReader;
    }

    public PxChunk getChunk() {
        return this.chunkReader.apply(chunkID);
    }

    public void addChild(ChunkNode child) {
        this.childs.add(child);
        if (child.parent != null && child.parent != this) {
            child.parent.childs.remove(child);
        }
        child.parent = this;
    }

    public ChunkNode root() {
        ChunkNode root = this;
        while (root.parent != null) {
            root = root.parent;
        }
        return root;
    }

    public void visit(Consumer<ChunkNode> visitor) {
        visitor.accept(this);
        for (var child : childs) {
            child.visit(visitor);
        }
    }

    public ChunkNode rank(PxChunk hitChunk) {
        if (this.parent == null) {
            this.rootRank += weightHit(hitChunk);
        }
        return this;
    }

    private static int weightHit(PxChunk hitChunk) {
        if (hitChunk.getMetadata().containsKey("java_code_section")) {
            JavaCodeSection section = JavaCodeSection.fromName(hitChunk.getMetadata().get("java_code_section"));
            switch (section) {
                case CLAZZ_FRAME:
                    return 2;
                case METHOD:
                case METHOD_DOC:
                    return 5;
                case DEPENDENCIE_INFO:
                    return 0;
                case IMPORTS:
                    return 1;
            }
        }
        return 0;
    }
}
