package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.mcp.QueryStatEntry;
import de.spraener.prjxp.mcp.QueryStats;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Collects query statistics for the dashboard. */
@Component
public class QueryStatsCollector {

    private final List<QueryStatEntry> recentQueries = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final ConcurrentLinkedQueue<Long> responseTimes = new ConcurrentLinkedQueue<>();

    public void recordQuery(String query, long responseTimeMs, int resultCount, boolean fromCache) {
        totalQueries.incrementAndGet();
        if (fromCache) cacheHits.incrementAndGet(); else cacheMisses.incrementAndGet();

        responseTimes.add(responseTimeMs);
        if (responseTimes.size() > 1000) responseTimes.poll();

        recentQueries.add(0, new QueryStatEntry(query, responseTimeMs, resultCount, fromCache));
        if (recentQueries.size() > 10) recentQueries.remove(recentQueries.size() - 1);
    }

    public QueryStats getStats() {
        List<Long> times = new ArrayList<>(responseTimes);
        double avg = times.stream().mapToLong(Long::longValue).average().orElse(0);
        double p95 = times.isEmpty() ? 0 : times.stream().sorted()
                .skip((long) (times.size() * 0.95))
                .findFirst().orElse(0L);

        return new QueryStats(
            totalQueries.get(), avg, p95,
            cacheHits.get(), cacheMisses.get(),
            new ArrayList<>(recentQueries)
        );
    }
}
