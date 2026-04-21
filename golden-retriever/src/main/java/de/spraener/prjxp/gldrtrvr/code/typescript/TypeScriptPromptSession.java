package de.spraener.prjxp.gldrtrvr.code.typescript;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.util.ValueContainer;
import de.spraener.prjxp.gldrtrvr.PxChunkDao;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkNode;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

@Data
public class TypeScriptPromptSession {
    private Map<String, PxChunk> chunkStore = new HashMap<>();
    private PxChunkDao chunkDao;
    private List<PxChunk> chunks;
    private List<ChunkNode> rootForrest = new ArrayList<>();
    private final int maxContentLength = 50000;

    public TypeScriptPromptSession(PxChunkDao chunkDao) {
        this.chunkDao = chunkDao;
    }

    record RankedPrompt(double rootRank, String treeContext) {
    }

    public void setChunks(List<PxChunk> chunks) {
        this.chunks = chunks;
        this.rootForrest.clear();
        for (var chunk : chunks) {
            ChunkNode root = findRootForChunk(chunk);
            if (root == null) {
                root = buildGraphToRoot(chunk).root();
                rootForrest.add(root);
            }
            root.rank(chunk);
        }
    }

    public String buildPrompt(BiFunction<PxChunk, String, String> promptModifier, Function<String, Boolean>... contextValidator) {
        List<RankedPrompt> rankedPrompts = new ArrayList<>();
        for (var r : this.rootForrest) {
            final ValueContainer<String> vcPrompt = new ValueContainer<>("");
            r.visit(c -> vcPrompt.setValue(promptModifier.apply(c.getChunk(), vcPrompt.getValue())));
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
            rankedPrompts.add(new RankedPrompt(r.getRootRank(), treeContext));
        }
        rankedPrompts.sort(Comparator.comparingDouble(RankedPrompt::rootRank));
        StringBuilder contextBuilder = new StringBuilder();
        for (var rp : rankedPrompts) {
            if (rp.rootRank() == 0) {
                break;
            }
            contextBuilder.append(rp.treeContext());
            if (contextBuilder.length() > maxContentLength) {
                break;
            }
        }
        return contextBuilder.toString();
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
                ChunkNode parentNode = findChunkNodeInTree(root, parent);
                if (parentNode != null) {
                    parentNode.addChild(new ChunkNode(chunk.getId(), chunk.getMetadata().get("typescript_code_section"), this::readChunk));
                }
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
        return chunkStore.computeIfAbsent(id, this::loadAndCombine);
    }

    private PxChunk loadAndCombine(String id) {
        return PxChunk.combine(chunkDao.findById(id));
    }

    private ChunkNode buildGraphToRoot(PxChunk c) {
        if (!StringUtils.hasText(c.getParent())) {
            return new ChunkNode(c.getId(), c.getMetadata().get("typescript_code_section"), this::readChunk);
        } else {
            List<PxChunk> parentChunk = chunkDao.findById(c.getParent());
            if (parentChunk == null || parentChunk.isEmpty()) {
                c.setParent(null);
                return buildGraphToRoot(c);
            }
            ChunkNode parent = buildGraphToRoot(PxChunk.combine(parentChunk));
            ChunkNode child = new ChunkNode(c.getId(), c.getMetadata().get("typescript_code_section"), this::readChunk);
            parent.addChild(child);
            return child;
        }
    }

    public Stream<PxChunk> getRootChunks() {
        return rootForrest.stream().map(ChunkNode::getChunk);
    }
}

