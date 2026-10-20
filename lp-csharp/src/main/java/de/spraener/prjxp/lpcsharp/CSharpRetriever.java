package de.spraener.prjxp.lpcsharp;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.retrieval.GoldenRetriever;
import de.spraener.prjxp.common.retrieval.SearchParams;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.common.store.PxChunkDaoProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The C# side of the Language Pack: turns scored C# chunks into coherent
 * retrieval context.
 *
 * <p>Self-contained by design — a plugin must not rely on Spring. It works on
 * the {@link ScoredChunk}s handed in by the host:
 * <ul>
 *   <li>filters down to C# chunks (metadata {@link CSharpCodeSection#METADATA_KEY}),</li>
 *   <li>reassembles split parts back into whole chunks,</li>
 *   <li>enriches a hit with its enclosing type frame, and</li>
 *   <li>renders a deterministic, budget-bounded prompt / search hits.</li>
 * </ul>
 *
 * <p>An optional {@link PxChunkDaoProvider} enables the two operations that need
 * the embedding store: fetching parts that were not returned by the vector search,
 * and fetching a parent frame that is not among the hits. When it is {@code null}
 * (the plugin's no-arg path) the retriever degrades gracefully to best-effort.
 */
public class CSharpRetriever implements GoldenRetriever {

    private final PxChunkDaoProvider chunkDaoProvider;

    /** Self-contained constructor used by the plugin (no store access). */
    public CSharpRetriever() {
        this(null);
    }

    /** Constructor with store access (tests / host wiring). */
    public CSharpRetriever(PxChunkDaoProvider chunkDaoProvider) {
        this.chunkDaoProvider = chunkDaoProvider;
    }

    @SafeVarargs
    @Override
    public final StringBuilder buildPromptForFindings(String projectName, List<ScoredChunk> chunks,
                                                      SearchParams params,
                                                      Function<String, Boolean>... contextValidators) {
        PxChunkDao dao = resolveDao(projectName);
        List<ScoredChunk> csharp = filterAndCombine(dao, chunks);
        if (csharp.isEmpty()) {
            return new StringBuilder();
        }
        Set<String> hitIds = new TreeSet<>();
        for (ScoredChunk sc : csharp) {
            hitIds.add(sc.chunk().getId());
        }

        StringBuilder prompt = new StringBuilder();
        int budget = params.getMaxContentLength();
        int count = 0;
        for (ScoredChunk sc : csharp) {
            if (count >= params.getMaxResult()) {
                break;
            }
            PxChunk enriched = enrich(dao, sc.chunk(), hitIds);
            String section = renderSection(enriched);
            if (!prompt.isEmpty() && prompt.length() + section.length() > budget) {
                break;
            }
            if (!validate(section, contextValidators)) {
                continue;
            }
            prompt.append(section);
            count++;
        }
        return prompt;
    }

    @SafeVarargs
    @Override
    public final List<SearchHit> retrieveSearchHits(String projectName, List<ScoredChunk> scoredChunks,
                                                    Function<String, Boolean>... contextValidators) {
        PxChunkDao dao = resolveDao(projectName);
        List<ScoredChunk> csharp = filterAndCombine(dao, scoredChunks);
        Set<String> hitIds = new TreeSet<>();
        for (ScoredChunk sc : csharp) {
            hitIds.add(sc.chunk().getId());
        }
        List<SearchHit> hits = new ArrayList<>();
        for (ScoredChunk sc : csharp) {
            PxChunk enriched = enrich(dao, sc.chunk(), hitIds);
            String snippet = SearchHit.preview(enriched.getContent());
            if (!validate(snippet, contextValidators)) {
                continue;
            }
            hits.add(SearchHit.from(enriched, sc.score(), snippet, "csharp"));
        }
        return hits;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private PxChunkDao resolveDao(String projectName) {
        if (chunkDaoProvider == null) {
            return null;
        }
        return chunkDaoProvider.get(projectName).orElse(null);
    }

    private boolean isCSharpChunk(PxChunk c) {
        return c != null && c.getMetadata() != null
                && c.getMetadata().containsKey(CSharpCodeSection.METADATA_KEY);
    }

    /**
     * Filters to C# chunks, reassembles split parts, keeps the best score per
     * chunk id and sorts deterministically (score desc, then id asc).
     */
    private List<ScoredChunk> filterAndCombine(PxChunkDao dao, List<ScoredChunk> chunks) {
        Map<String, List<ScoredChunk>> byId = new LinkedHashMap<>();
        for (ScoredChunk sc : chunks) {
            if (isCSharpChunk(sc.chunk())) {
                byId.computeIfAbsent(sc.chunk().getId(), k -> new ArrayList<>()).add(sc);
            }
        }
        List<ScoredChunk> result = new ArrayList<>();
        for (Map.Entry<String, List<ScoredChunk>> e : byId.entrySet()) {
            List<ScoredChunk> parts = e.getValue();
            double bestScore = parts.stream().mapToDouble(ScoredChunk::score).max().orElse(0.0);
            PxChunk combined = combineParts(dao, e.getKey(), parts);
            if (combined != null) {
                result.add(new ScoredChunk(combined, bestScore));
            }
        }
        result.sort(Comparator.comparingDouble(ScoredChunk::score).reversed()
                .thenComparing(sc -> sc.chunk().getId() == null ? "" : sc.chunk().getId()));
        return result;
    }

    /**
     * Reassembles the parts of one chunk. Uses all parts when they are present;
     * otherwise falls back to the store (if available) or to the parts at hand.
     */
    private PxChunk combineParts(PxChunkDao dao, String id, List<ScoredChunk> parts) {
        PxChunk first = parts.get(0).chunk();
        int total = first.getTotal();
        Set<Integer> present = new TreeSet<>();
        for (ScoredChunk sc : parts) {
            present.add(sc.chunk().getPart());
        }
        List<PxChunk> partChunks = new ArrayList<>();
        boolean complete = total <= 1 || present.size() >= total;
        if (!complete && dao != null) {
            List<PxChunk> fromDao = dao.findById(id);
            if (fromDao != null && !fromDao.isEmpty()) {
                partChunks.addAll(fromDao);
            }
        }
        if (partChunks.isEmpty()) {
            for (ScoredChunk sc : parts) {
                partChunks.add(sc.chunk());
            }
        }
        return PxChunk.combine(partChunks);
    }

    /**
     * Prepends the enclosing type frame to a hit, unless the frame is already a
     * hit in its own right (to avoid duplicating it in the prompt).
     */
    private PxChunk enrich(PxChunkDao dao, PxChunk chunk, Set<String> hitIds) {
        String parent = chunk.getParent();
        if (parent == null || parent.isBlank() || hitIds.contains(parent)) {
            return chunk;
        }
        PxChunk parentChunk = null;
        if (dao != null) {
            List<PxChunk> fromDao = dao.findById(parent);
            if (fromDao != null && !fromDao.isEmpty()) {
                parentChunk = PxChunk.combine(fromDao);
            }
        }
        if (parentChunk == null || parentChunk.getContent() == null || parentChunk.getContent().isBlank()) {
            return chunk;
        }
        String enrichedContent = "// enclosing type: " + parent + "\n"
                + parentChunk.getContent() + "\n\n"
                + (chunk.getContent() == null ? "" : chunk.getContent());
        return PxChunk.create(
                c -> c.setId(chunk.getId()),
                c -> c.setMimeType(chunk.getMimeType()),
                c -> c.setFile(chunk.getFile()),
                c -> c.setParent(chunk.getParent()),
                c -> c.setPart(chunk.getPart()),
                c -> c.setTotal(chunk.getTotal()),
                c -> c.setFromLine(chunk.getFromLine()),
                c -> c.setToLine(chunk.getToLine()),
                c -> c.setSize(chunk.getSize()),
                c -> c.setOverlap(chunk.getOverlap()),
                c -> c.setContent(enrichedContent),
                c -> c.getMetadata().putAll(chunk.getMetadata())
        );
    }

    private String renderSection(PxChunk chunk) {
        String section = chunk.getMetadata() == null ? null
                : chunk.getMetadata().get(CSharpCodeSection.METADATA_KEY);
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(chunk.getId());
        if (section != null) {
            sb.append(" [").append(section).append("]");
        }
        sb.append(" ===\n");
        sb.append(chunk.getContent() == null ? "" : chunk.getContent());
        sb.append("\n\n");
        return sb.toString();
    }

    private boolean validate(String text, Function<String, Boolean>... contextValidators) {
        if (contextValidators == null) {
            return true;
        }
        for (Function<String, Boolean> v : contextValidators) {
            if (v != null && !v.apply(text)) {
                return false;
            }
        }
        return true;
    }
}
