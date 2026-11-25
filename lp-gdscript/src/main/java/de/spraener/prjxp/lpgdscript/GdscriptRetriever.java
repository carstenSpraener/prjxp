package de.spraener.prjxp.lpgdscript;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.retrieval.ContentLevel;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.retrieval.PointerRenderer;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Retriever for GDScript code chunks. Filters the hit set to GDScript code
 * sections, groups parts by id (best score wins), combines multi-part chunks
 * (from the hit set or via the dao), and enriches functions with their
 * enclosing class header and {@code ##} doc comment.
 *
 * <p>Self-contained mode (no-arg constructor, no dao): no enrichment, the
 * hit content is rendered as-is.
 */
public class GdscriptRetriever implements GoldenRetriever {

    private final PxChunkDaoProvider chunkDaoProvider;
    private final PointerRenderer pointerRenderer = new PointerRenderer();

    public GdscriptRetriever() {
        this(null);
    }

    public GdscriptRetriever(PxChunkDaoProvider chunkDaoProvider) {
        this.chunkDaoProvider = chunkDaoProvider;
    }

    private PxChunkDao resolveDao(String projectName) {
        if (chunkDaoProvider == null) {
            return null;
        }
        return chunkDaoProvider.get(projectName).orElse(null);
    }

    private record Ranked(PxChunk chunk, double score) {
    }

    @Override
    @SafeVarargs
    public final StringBuilder buildPromptForFindings(String projectName, List<ScoredChunk> chunks,
                                                      SearchParams params,
                                                      Function<String, Boolean>... contextValidators) {
        PxChunkDao dao = resolveDao(projectName);
        List<Ranked> ranked = filterAndCombine(dao, chunks);
        if (params.effectiveContentLevel() == ContentLevel.POINTERS) {
            return buildPointersPrompt(projectName, ranked, params);
        }
        Set<String> hitIds = hitIds(ranked);

        StringBuilder prompt = new StringBuilder();
        int count = 0;
        for (Ranked r : ranked) {
            if (count >= params.getMaxResult()) {
                break;
            }
            PxChunk enriched = enrich(dao, r.chunk(), hitIds);
            String block = "=== " + r.chunk().getId() + " [" + sectionOf(r.chunk()) + "] ===\n"
                    + enriched.getContent() + "\n\n";
            if (prompt.length() + block.length() > params.getMaxContentLength()) {
                break;
            }
            prompt.append(block);
            count++;
        }

        String text = prompt.toString();
        if (contextValidators != null) {
            for (Function<String, Boolean> validator : contextValidators) {
                if (validator != null && !validator.apply(text)) {
                    return new StringBuilder();
                }
            }
        }
        return prompt;
    }

    @Override
    @SafeVarargs
    public final List<SearchHit> retrieveSearchHits(String projectName, List<ScoredChunk> chunks,
                                                    Function<String, Boolean>... contextValidators) {
        PxChunkDao dao = resolveDao(projectName);
        List<Ranked> ranked = filterAndCombine(dao, chunks);
        Set<String> hitIds = hitIds(ranked);

        List<SearchHit> hits = new ArrayList<>();
        for (Ranked r : ranked) {
            PxChunk enriched = enrich(dao, r.chunk(), hitIds);
            hits.add(SearchHit.from(enriched, r.score(), SearchHit.preview(enriched.getContent()), "gdscript"));
        }
        return hits;
    }

    /**
     * POINTERS layer: one dual-graph reference block per hit instead of the rendered section,
     * bounded by {@code maxResult} and the content budget.
     */
    private StringBuilder buildPointersPrompt(String projectName, List<Ranked> ranked, SearchParams params) {
        StringBuilder prompt = new StringBuilder();
        int count = 0;
        for (Ranked r : ranked) {
            if (count >= params.getMaxResult()) {
                break;
            }
            String block = pointerRenderer.render(projectName, r.chunk(), r.score());
            if (prompt.length() + block.length() > params.getMaxContentLength()) {
                break;
            }
            prompt.append(block);
            count++;
        }
        return prompt;
    }

    // ------------------------------------------------------------------
    // Pipeline
    // ------------------------------------------------------------------

    /** Filters to GDScript code sections, groups by id, combines parts, sorts by score DESC then id ASC. */
    private List<Ranked> filterAndCombine(PxChunkDao dao, List<ScoredChunk> chunks) {
        Map<String, List<ScoredChunk>> byId = new LinkedHashMap<>();
        if (chunks != null) {
            for (ScoredChunk sc : chunks) {
                if (sc == null) {
                    continue;
                }
                PxChunk c = sc.chunk();
                if (c == null || c.getId() == null) {
                    continue;
                }
                Map<String, String> metadata = c.getMetadata();
                if (metadata == null || metadata.get(GdscriptCodeSection.METADATA_KEY) == null) {
                    continue;
                }
                byId.computeIfAbsent(c.getId(), k -> new ArrayList<>()).add(sc);
            }
        }

        List<Ranked> ranked = new ArrayList<>(byId.size());
        for (Map.Entry<String, List<ScoredChunk>> entry : byId.entrySet()) {
            List<ScoredChunk> group = entry.getValue();
            double best = group.stream().mapToDouble(ScoredChunk::score).max().orElse(0.0);
            ranked.add(new Ranked(combineGroup(dao, entry.getKey(), group), best));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed()
                .thenComparing(r -> r.chunk().getId()));
        return ranked;
    }

    /**
     * Combines the parts of one id: single-part groups yield the best-scoring
     * part; multi-part groups are combined when all parts are present in the
     * hit set, or fetched via {@code dao.findById} when parts are missing.
     * Falls back to the best-scoring part when nothing better is possible.
     */
    private PxChunk combineGroup(PxChunkDao dao, String id, List<ScoredChunk> group) {
        int total = group.stream().mapToInt(sc -> sc.chunk().getTotal()).max().orElse(1);
        if (total <= 1) {
            return bestPart(group);
        }
        Set<Integer> present = group.stream()
                .mapToInt(sc -> sc.chunk().getPart())
                .boxed()
                .collect(Collectors.toSet());
        boolean allPresent = true;
        for (int i = 0; i < total; i++) {
            if (!present.contains(i)) {
                allPresent = false;
                break;
            }
        }
        if (allPresent) {
            PxChunk combined = PxChunk.combine(
                    group.stream().map(ScoredChunk::chunk).collect(Collectors.toList()));
            if (combined != null) {
                return combined;
            }
        } else if (dao != null) {
            PxChunk combined = PxChunk.combine(dao.findById(id));
            if (combined != null) {
                return combined;
            }
        }
        return bestPart(group);
    }

    private PxChunk bestPart(List<ScoredChunk> group) {
        return group.stream()
                .max(Comparator.comparingDouble(ScoredChunk::score))
                .map(ScoredChunk::chunk)
                .orElse(null);
    }

    private static Set<String> hitIds(List<Ranked> ranked) {
        Set<String> ids = new HashSet<>();
        for (Ranked r : ranked) {
            if (r.chunk().getId() != null) {
                ids.add(r.chunk().getId());
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------
    // Enrichment
    // ------------------------------------------------------------------

    /**
     * Prepends context above the hit: the enclosing class header (marked with
     * a GDScript {@code #} comment) and, for functions, the {@code ##} doc
     * comment. Parts that are hits themselves are skipped — they are rendered
     * as their own sections.
     */
    @SuppressWarnings("unchecked") // PxChunk.create(Consumer<PxChunk>...) varargs; a single consumer is always passed
    private PxChunk enrich(PxChunkDao dao, PxChunk chunk, Set<String> hitIds) {
        if (dao == null) {
            return chunk;
        }
        StringBuilder head = new StringBuilder();

        String parent = chunk.getParent();
        if (parent != null && !parent.isBlank() && !hitIds.contains(parent)) {
            PxChunk parentChunk = PxChunk.combine(dao.findById(parent));
            if (parentChunk != null && parentChunk.getContent() != null) {
                head.append("# enclosing class: ").append(parent).append('\n');
                head.append(parentChunk.getContent());
                head.append('\n');
            }
        }

        if (GdscriptCodeSection.FUNC.getName().equals(sectionOf(chunk))) {
            String docId = chunk.getId() + ".doc";
            if (!hitIds.contains(docId)) {
                PxChunk doc = PxChunk.combine(dao.findById(docId));
                if (doc != null && doc.getContent() != null) {
                    head.append(doc.getContent());
                }
            }
        }

        if (head.length() == 0) {
            return chunk;
        }
        String content = chunk.getContent() == null ? "" : chunk.getContent();
        Map<String, String> metadata = chunk.getMetadata();
        return PxChunk.create(c -> {
            c.setId(chunk.getId());
            c.setMimeType(chunk.getMimeType());
            c.setFile(chunk.getFile());
            c.setParent(chunk.getParent());
            c.setPart(chunk.getPart());
            c.setTotal(chunk.getTotal());
            c.setFromLine(chunk.getFromLine());
            c.setToLine(chunk.getToLine());
            c.setSize(chunk.getSize());
            c.setOverlap(chunk.getOverlap());
            c.setEmbeddingPrefix(chunk.getEmbeddingPrefix());
            c.setProject(chunk.getProject());
            c.setMetadata(new HashMap<>(metadata == null ? Map.of() : metadata));
            c.setContent(head + content);
        });
    }

    private static String sectionOf(PxChunk chunk) {
        Map<String, String> metadata = chunk.getMetadata();
        return metadata == null ? null : metadata.get(GdscriptCodeSection.METADATA_KEY);
    }
}
