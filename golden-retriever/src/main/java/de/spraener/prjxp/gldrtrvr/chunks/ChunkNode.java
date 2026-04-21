package de.spraener.prjxp.gldrtrvr.chunks;

import de.spraener.prjxp.common.code.java.JavaCodeSection;
import de.spraener.prjxp.common.code.typescript.TypeScriptCodeSection;
import de.spraener.prjxp.common.model.PxChunk;
import lombok.Data;
import lombok.ToString;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

@Data
public class ChunkNode {
    private Function<String, PxChunk> chunkReader;
    @ToString.Exclude
    private ChunkNode parent;
    private String type;
    private String chunkID;
    private double rootRank = 0; // only maintained for root nodes (parent == null)
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
        Collections.sort(childs, (c1, c2)->c1.chunkID.compareTo(c2.chunkID));
        for (var child : childs) {
            child.visit(visitor);
        }
    }

    public ChunkNode rank(PxChunk hitChunk) {
        if (this.parent == null) {
            this.rootRank += ChunkRankingService.rank(hitChunk);
        }
        return this;
    }
}
