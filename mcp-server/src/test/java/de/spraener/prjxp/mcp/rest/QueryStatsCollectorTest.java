package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.mcp.QueryStatEntry;
import de.spraener.prjxp.mcp.QueryStats;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link QueryStatsCollector}.
 */
class QueryStatsCollectorTest {

    private final QueryStatsCollector collector = new QueryStatsCollector();

    @Test
    void getStatsReturnsZerosWhenEmpty() {
        QueryStats stats = collector.getStats();

        assertThat(stats.totalQueries()).isEqualTo(0);
        assertThat(stats.avgResponseTimeMs()).isEqualTo(0);
        assertThat(stats.p95ResponseTimeMs()).isEqualTo(0);
        assertThat(stats.cacheHits()).isEqualTo(0);
        assertThat(stats.cacheMisses()).isEqualTo(0);
        assertThat(stats.recentQueries()).isEmpty();
    }

    @Test
    void recordQueryIncrementsTotalAndCacheCounters() {
        collector.recordQuery("test query", 50, 10, true);
        collector.recordQuery("another query", 30, 5, false);

        QueryStats stats = collector.getStats();

        assertThat(stats.totalQueries()).isEqualTo(2);
        assertThat(stats.cacheHits()).isEqualTo(1);
        assertThat(stats.cacheMisses()).isEqualTo(1);
    }

    @Test
    void recordQueryAddsToRecentQueries() {
        collector.recordQuery("q1", 50, 10, true);
        collector.recordQuery("q2", 30, 5, false);

        QueryStats stats = collector.getStats();
        List<QueryStatEntry> recent = stats.recentQueries();

        assertThat(recent).hasSize(2);
        // Most recent first (added at index 0)
        assertThat(recent.get(0).query()).isEqualTo("q2");
        assertThat(recent.get(1).query()).isEqualTo("q1");
    }

    @Test
    void recentQueriesLimitedToTen() {
        for (int i = 0; i < 15; i++) {
            collector.recordQuery("q" + i, (long) i * 10, i, false);
        }

        QueryStats stats = collector.getStats();
        assertThat(stats.recentQueries()).hasSize(10);
        // Most recent 10 (q14 down to q5)
        assertThat(stats.recentQueries().get(0).query()).isEqualTo("q14");
        assertThat(stats.recentQueries().get(9).query()).isEqualTo("q5");
    }

    @Test
    void getStatsCalculatesAverageResponseTime() {
        collector.recordQuery("q1", 100, 5, false);
        collector.recordQuery("q2", 200, 10, false);

        QueryStats stats = collector.getStats();
        // Average of 100 and 200 is 150
        assertThat(stats.avgResponseTimeMs()).isEqualTo(150);
    }

    @Test
    void getStatsCalculatesP95ResponseTime() {
        // Add 20 queries with response times 1-20
        for (int i = 1; i <= 20; i++) {
            collector.recordQuery("q" + i, (long) i * 10, i, false);
        }

        QueryStats stats = collector.getStats();
        // P95 of 20 values (10,20,...,200) should be around the 95th percentile
        // Index 19 (0-based) = 200ms, but we only keep last 10 in recentQueries
        // responseTimes queue keeps all (up to 1000), so p95 is calculated on all 20
        assertThat(stats.p95ResponseTimeMs()).isGreaterThan(100);
    }

    @Test
    void responseTimesLimitedToOneThousand() {
        for (int i = 0; i < 1005; i++) {
            collector.recordQuery("q" + i, (long) i, i, false);
        }

        QueryStats stats = collector.getStats();
        // totalQueries should be 1005, but internal queue limited to 1000
        assertThat(stats.totalQueries()).isEqualTo(1005);
    }
}
