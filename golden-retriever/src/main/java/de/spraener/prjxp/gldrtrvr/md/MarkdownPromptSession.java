package de.spraener.prjxp.gldrtrvr.md;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.util.ValueContainer;
import de.spraener.prjxp.gldrtrvr.PxChunkDao;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkNode;
import de.spraener.prjxp.gldrtrvr.chunks.ChunkRankingService;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

@Data
public class MarkdownPromptSession {
    private Map<String, PxChunk> chunkStore = new HashMap<>();
    private PxChunkDao chunkDao;
    private List<PxChunk> chunks;
    private List<ChunkNode> rootForrest = new ArrayList<>();
    private final int maxContentLength = 5000;
    private final ChunkRankingService rankingService;

    public MarkdownPromptSession(PxChunkDao chunkDao, ChunkRankingService rankingService) {
        this.chunkDao = chunkDao;
        this.rankingService = rankingService;
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
            root.rank(chunk, rankingService);
        }
    }

    public String buildPrompt(BiFunction<PxChunk, String, String> promptModifier, Function<String, Boolean>... contextValidator) {
        StringBuilder context = new StringBuilder();
        List<RankedPrompt> rankedPrompts = new ArrayList<>();
        for (var r : this.rootForrest) {
            final ValueContainer<String> vcPrompt = new ValueContainer<>("");
            r.visit(c -> {
                vcPrompt.setValue(promptModifier.apply(c.getChunk(), vcPrompt.getValue()));
            });
            String treeContext = addUserDefinedPrompt(r.getChunk().getMetadata(), vcPrompt.getValue());
            if (contextValidator != null && contextValidator.length > 0) {
                boolean valid = true;
                for (var v : contextValidator) {
                    valid &= v.apply(treeContext);
                }
                if (!valid) {
                    continue;
                }
            }
            rankedPrompts.add(new RankedPrompt((100.0*r.getRootRank())/treeContext.length(), treeContext));
        }
        rankedPrompts.sort(Comparator.comparingDouble(RankedPrompt::rootRank));

        for (var rp : rankedPrompts) {
            if (rp.rootRank() == 0) {
                break;
            }
            if (context.length() + rp.treeContext().length() > maxContentLength) {
                continue;
            }
            context.append(rp.treeContext());
        }
        return context.toString();
    }

    private String addUserDefinedPrompt(Map<String, String> metadata, String value) {
        if(metadata.containsKey("promptFormat") ) {
            String promptFormat = metadata.get("promptFormat");
            for( var e : metadata.entrySet() ) {
                promptFormat = promptFormat.replace("{" + e.getKey() + "}", e.getValue());
            }
            return promptFormat+"\n"+value+"\n";
        } else {
            return value;
        }
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
                // Wir nutzen hier "md_section" als Typ-Marker für Markdown-Hierarchien
                findChunkNodeInTree(root, parent).addChild(new ChunkNode(chunk.getId(), "md_section", this::readChunk));
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
            return new ChunkNode(c.getId(), "file", this::readChunk);
        } else {
            List<PxChunk> parentChunk = chunkDao.findById(c.getParent());
            if (parentChunk == null || parentChunk.isEmpty()) {
                c.setParent(null);
                return buildGraphToRoot(c);
            }
            ChunkNode parent = buildGraphToRoot(PxChunk.combine(parentChunk));
            ChunkNode child = new ChunkNode(c.getId(), "md_section", this::readChunk);
            parent.addChild(child);
            return child;
        }
    }

    public Stream<PxChunk> getRootChunks() {
        return rootForrest.stream().map(ChunkNode::getChunk);
    }
}