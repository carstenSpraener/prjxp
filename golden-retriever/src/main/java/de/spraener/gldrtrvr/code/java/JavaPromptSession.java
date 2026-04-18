package de.spraener.gldrtrvr.code.java;

import de.spraener.gldrtrvr.PxChunkDao;
import de.spraener.gldrtrvr.chunks.ChunkNode;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.util.ValueContainer;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

@Data
public class JavaPromptSession {
    private Map<String, PxChunk> chunkStore = new HashMap<>();
    private PxChunkDao chunkDao;
    private List<PxChunk> chunks;
    private List<ChunkNode> rootForrest = new ArrayList<>();

    public JavaPromptSession(PxChunkDao chunkDao) {
        this.chunkDao = chunkDao;
    }

    public void setChunks(List<PxChunk> chunks) {
        this.chunks = chunks;
        this.rootForrest.clear();
        for (var chunk : chunks) {
            ChunkNode root = findRootForChunk(chunk);
            if (root == null) {
                rootForrest.add(buildGraphToRoot(chunk).root());
            }
        }
    }

    public String buildPrompt(BiFunction<PxChunk, String, String> promptModifier, Function<String, Boolean>... contextValidator) {
        String context = "";
        for (var r : this.rootForrest) {
            final ValueContainer<String> vcPrompt = new ValueContainer<String>("");
            r.visit(c -> {
                vcPrompt.setValue(promptModifier.apply(c.getChunk(), vcPrompt.getValue()));
            });
            String treeContext = vcPrompt.getValue();
            if (contextValidator != null) {
                boolean valid = true;
                for (var v : contextValidator) {
                    valid &= v.apply(treeContext);
                }
                if (!valid) {
                    continue;
                }
            }
            context += treeContext;
        }
        return context;
    }

    private ChunkNode findRootForChunk(PxChunk chunk) {
        for (var r : rootForrest) {
            if (findChunkNodeInTree(r, chunk) != null) {
                return r;
            }
        }
        if (chunk.getParent() != null) {
            PxChunk parent = readChunk(chunk.getParent());
            if (parent == null) {
                return null;
            }
            ChunkNode root = findRootForChunk(parent);
            if (root != null) {
                findChunkNodeInTree(root, parent).addChild(new ChunkNode(chunk.getId(), chunk.getMetadata().get("java_code_section"), this::readChunk));
            }
            return root;
        }
        return null;
    }

    private ChunkNode findChunkNodeInTree(ChunkNode r, PxChunk chunk) {
        if (r.getChunkID().equals(chunk.getId())) {
            return r;
        }
        for (var child : r.getChilds()) {
            ChunkNode found = findChunkNodeInTree(child, chunk);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public PxChunk readChunk(String id) {
        return chunkStore.computeIfAbsent(id, k -> loadAndCombine(k));
    }

    private PxChunk loadAndCombine(String id) {
        return PxChunk.combine(chunkDao.findById(id));
    }

    private ChunkNode buildGraphToRoot(PxChunk c) {
        if (!StringUtils.hasText(c.getParent())) {
            return new ChunkNode(c.getId(), c.getMetadata().get("java_code_section"), this::readChunk);
        } else {
            List<PxChunk> parentChunk = chunkDao.findById(c.getParent());
            if (parentChunk == null || parentChunk.isEmpty()) {
                c.setParent(null);
                return buildGraphToRoot(c);
            }
            ChunkNode parent = buildGraphToRoot(PxChunk.combine(parentChunk));
            ChunkNode child = new ChunkNode(c.getId(), c.getMetadata().get("java_code_section"), this::readChunk);
            parent.addChild(child);
            return child;
        }
    }

    public Stream<PxChunk> getRootChunks() {
        return rootForrest.stream().map(ChunkNode::getChunk);
    }
}
