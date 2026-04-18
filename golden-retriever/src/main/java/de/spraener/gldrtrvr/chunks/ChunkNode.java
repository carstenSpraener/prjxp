package de.spraener.gldrtrvr.chunks;

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
}
