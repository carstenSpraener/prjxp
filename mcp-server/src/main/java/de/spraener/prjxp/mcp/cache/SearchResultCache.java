package de.spraener.prjxp.mcp.cache;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Bounded in-memory cache for search results with TTL and LRU eviction.
 *
 * <p>Thread-safe: all map access is synchronized. The loader is executed
 * <b>outside</b> the lock so an expensive search does not block other cache
 * operations. Deliberately <b>no stampede lock</b>: concurrent identical
 * queries may load the result twice; that is acceptable for this use case.
 *
 * <p>Keys must follow the convention {@code project + "\u0000" + ...} so that
 * {@link #invalidateProject(String)} can filter by prefix.
 */
@Component
public class SearchResultCache {

    /** Result of a cache lookup: the value and whether it came from the cache. */
    public record Lookup<T>(T value, boolean hit) {}

    private static final char PROJECT_SEPARATOR = '\u0000';
    private static final char FIELD_SEPARATOR = '\u0001';
    private static final char PAIR_SEPARATOR = '\u0002';
    private static final String NULL_LITERAL = "null";

    private final int maxEntries;
    private final long ttlNanos;
    private final LongSupplier nanoClock;

    private final LinkedHashMap<String, Entry> map = new LinkedHashMap<>(16, 0.75f, true);
    private final java.util.concurrent.atomic.AtomicLong hits = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong misses = new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * Spring constructor. Explicitly {@code @Autowired} because the class also has a
     * package-private test constructor — with two constructors Spring would otherwise fall
     * back to a (non-existent) no-arg constructor and fail to create the bean.
     */
    @Autowired
    public SearchResultCache(
            @Value("${prjxp.metrics.cache.max-entries:512}") int maxEntries,
            @Value("${prjxp.metrics.cache.ttl-seconds:60}") long ttlSeconds) {
        this(maxEntries, ttlSeconds, System::nanoTime);
    }

    /** For tests: injectable nano clock. */
    SearchResultCache(int maxEntries, long ttlSeconds, LongSupplier nanoClock) {
        this.maxEntries = maxEntries;
        this.ttlNanos = ttlSeconds * 1_000_000_000L;
        this.nanoClock = nanoClock;
    }

    /**
     * Returns the cached value or loads it via the supplier.
     *
     * <p>The loader runs outside the lock (no stampede lock: concurrent
     * identical queries may load twice). {@code null} results are not cached.
     * Expired or type-mismatched entries are removed and count as misses.
     */
    public <T> Lookup<T> get(String key, Class<T> type, Supplier<T> loader) {
        synchronized (map) {
            Entry entry = map.get(key);
            if (entry != null) {
                if (nanoClock.getAsLong() < entry.expiresAtNanos && type.isInstance(entry.value)) {
                    hits.incrementAndGet();
                    @SuppressWarnings("unchecked")
                    T value = (T) entry.value;
                    return new Lookup<>(value, true);
                }
                map.remove(key);
            }
        }
        misses.incrementAndGet();
        T value = loader.get();
        if (value != null && maxEntries > 0) {
            synchronized (map) {
                map.put(key, new Entry(value, nanoClock.getAsLong() + ttlNanos));
                while (map.size() > maxEntries) {
                    map.remove(map.keySet().iterator().next());
                }
            }
        }
        return new Lookup<>(value, false);
    }

    /** Number of cache hits since start. */
    public long hits() {
        return hits.get();
    }

    /** Number of cache misses since start. */
    public long misses() {
        return misses.get();
    }

    /** Clears the entire cache (e.g. after reindex). */
    public void clear() {
        synchronized (map) {
            map.clear();
        }
    }

    /** Removes all entries of a project (key prefix). */
    public void invalidateProject(String projectName) {
        String prefix = projectName + PROJECT_SEPARATOR;
        synchronized (map) {
            map.keySet().removeIf(key -> key.startsWith(prefix));
        }
    }

    /** Key for vector searches: project prefix + query parameters + sorted filters. */
    public static String vectorKey(String project, String query, String language, int limit,
                                   Map<String, String> filters) {
        return project + PROJECT_SEPARATOR + "vector"
                + FIELD_SEPARATOR + field(query)
                + FIELD_SEPARATOR + field(language)
                + FIELD_SEPARATOR + limit
                + FIELD_SEPARATOR + serializeFilters(filters);
    }

    /** Key for the vector tool: project prefix + question + parameters. */
    public static String vectorToolKey(String project, String question, Double similarity,
                                       Integer maxResults, String sourceType, Boolean includeTests,
                                       Integer tokenBudget) {
        return project + PROJECT_SEPARATOR + "vectorTool"
                + FIELD_SEPARATOR + field(question)
                + FIELD_SEPARATOR + field(similarity)
                + FIELD_SEPARATOR + field(maxResults)
                + FIELD_SEPARATOR + field(sourceType)
                + FIELD_SEPARATOR + field(includeTests)
                + FIELD_SEPARATOR + field(tokenBudget);
    }

    private static String field(Object value) {
        return value == null ? NULL_LITERAL : String.valueOf(value);
    }

    /** Serializes filters as sorted {@code key=value} pairs; null values become the literal {@code null}. */
    private static String serializeFilters(Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        return filters.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(e -> e.getKey() + "=" + field(e.getValue()))
                .collect(Collectors.joining(String.valueOf(PAIR_SEPARATOR)));
    }

    private record Entry(Object value, long expiresAtNanos) {}
}
