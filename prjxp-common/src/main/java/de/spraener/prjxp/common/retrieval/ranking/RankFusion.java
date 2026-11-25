package de.spraener.prjxp.common.retrieval.ranking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reciprocal rank fusion over independent query signals.
 * <p>
 * The signals in this code base score on different, non-comparable scales (cosine 0..1, BM25
 * unbounded, code-section rank 0..5, raw match counts). Adding those values is meaningless, so
 * only the <em>rank</em> of an entry within its signal is used:
 *
 * <pre>
 *     score(id) = Σ_signal  weight / (k + rank(id))
 * </pre>
 *
 * An entry that a signal does not contain contributes nothing there — it is not penalized, and it
 * cannot be boosted by a single enthusiastic signal either: two mid-rank confirmations beat one
 * top hit, which is exactly the property that makes a name match and a content match agree.
 * <p>
 * The result is deterministic: ties are broken by file, then by id. The benchmark repeats must
 * stay byte-identical, so no comparator may depend on iteration order of a {@code HashMap}.
 */
public final class RankFusion {

    public static final int DEFAULT_K = 60;

    public static final RankFusion DEFAULT = new RankFusion(DEFAULT_K);

    private final int k;

    public RankFusion(int k) {
        if (k < 0) {
            throw new IllegalArgumentException("k must not be negative: " + k);
        }
        this.k = k;
    }

    public RankFusion withK(int otherK) {
        return new RankFusion(otherK);
    }

    public int k() {
        return k;
    }

    /** Fused chunk ranking. {@code limit <= 0} yields an empty list. */
    public List<RankedItem> rankItems(List<WeightedSignal> signals, int limit) {
        if (limit <= 0 || signals == null || signals.isEmpty()) {
            return List.of();
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, RankedItem> representatives = new LinkedHashMap<>();
        for (WeightedSignal signal : signals) {
            if (signal == null || !signal.isActive()) {
                continue;
            }
            Set<String> seen = new HashSet<>();
            int rank = 0;
            for (RankedItem item : signal.items()) {
                String key = keyOf(item);
                if (key == null || !seen.add(key)) {
                    continue;   // a duplicate only contributes with its best rank
                }
                rank++;
                scores.merge(key, signal.weight() / (k + rank), Double::sum);
                representatives.putIfAbsent(key, item);
            }
        }
        List<Map.Entry<String, Double>> entries = new ArrayList<>(scores.entrySet());
        entries.sort(order(representatives));
        List<RankedItem> result = new ArrayList<>(Math.min(limit, entries.size()));
        for (Map.Entry<String, Double> entry : entries) {
            if (result.size() >= limit) {
                break;
            }
            RankedItem representative = representatives.get(entry.getKey());
            result.add(new RankedItem(entry.getKey(), representative.file(), entry.getValue()));
        }
        return result;
    }

    /**
     * Fused file ranking: one entry per file. A signal ranks a file by its <em>best</em> entry —
     * so a name hit (a file id) and a content hit (a chunk id) of the same file confirm each
     * other instead of competing as unrelated keys. The chunk count is carried for information
     * only and never influences the order.
     */
    public List<RankedFile> rankFiles(List<WeightedSignal> signals, int limit) {
        if (limit <= 0 || signals == null || signals.isEmpty()) {
            return List.of();
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Integer> chunksByFile = new LinkedHashMap<>();
        for (WeightedSignal signal : signals) {
            if (signal == null || !signal.isActive()) {
                continue;
            }
            Map<String, Integer> fileRank = new LinkedHashMap<>();
            for (RankedItem item : signal.items()) {
                String file = item == null ? null : trimToNull(item.file());
                if (file == null) {
                    continue;   // a hit without a file cannot be aggregated to one
                }
                fileRank.putIfAbsent(file, fileRank.size() + 1);
                chunksByFile.merge(file, 1, Integer::sum);
            }
            fileRank.forEach((file, rank) -> scores.merge(file, signal.weight() / (k + rank), Double::sum));
        }
        List<String> files = new ArrayList<>(scores.keySet());
        files.sort(Comparator.comparingDouble((String file) -> scores.get(file)).reversed()
                .thenComparing(Comparator.naturalOrder()));
        List<RankedFile> result = new ArrayList<>(Math.min(limit, files.size()));
        for (String file : files) {
            if (result.size() >= limit) {
                break;
            }
            result.add(new RankedFile(file, scores.get(file), chunksByFile.get(file)));
        }
        return result;
    }

    private static Comparator<Map.Entry<String, Double>> order(Map<String, RankedItem> representatives) {
        return (left, right) -> {
            int byScore = Double.compare(right.getValue(), left.getValue());
            if (byScore != 0) {
                return byScore;
            }
            int byFile = fileOf(representatives.get(left.getKey()))
                    .compareTo(fileOf(representatives.get(right.getKey())));
            if (byFile != 0) {
                return byFile;
            }
            return left.getKey().compareTo(right.getKey());
        };
    }

    private static String keyOf(RankedItem item) {
        if (item == null) {
            return null;
        }
        String id = trimToNull(item.id());
        return id != null ? id : trimToNull(item.file());
    }

    private static String fileOf(RankedItem item) {
        String file = item == null ? null : trimToNull(item.file());
        return file == null ? "" : file;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
