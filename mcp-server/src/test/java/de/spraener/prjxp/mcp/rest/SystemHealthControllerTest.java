package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.mcp.QueryStats;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link SystemHealthController} using MockMvc.
 */
@WebMvcTest(controllers = SystemHealthController.class)
@Import(QueryStatsCollector.class)
@TestPropertySource(properties = "prjxp.hub.enabled=true")
class SystemHealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QueryStatsCollector queryStatsCollector;

    @Test
    void getSystemHealthReturnsHealthMetrics() throws Exception {
        mockMvc.perform(get("/api/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cpuUsage").exists())
                .andExpect(jsonPath("$.totalMemory").exists())
                .andExpect(jsonPath("$.usedMemory").exists())
                .andExpect(jsonPath("$.freeDiskSpace").exists())
                .andExpect(jsonPath("$.totalDiskSpace").exists())
                .andExpect(jsonPath("$.activeConnections").exists());
    }

    @Test
    void getSystemHealthValuesArePositive() throws Exception {
        mockMvc.perform(get("/api/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMemory").isNotEmpty())
                .andExpect(jsonPath("$.totalDiskSpace").isNotEmpty());
    }

    @Test
    void getQueryStatsReturnsEmptyStatsWhenNoQueries() throws Exception {
        when(queryStatsCollector.getStats()).thenReturn(
                new QueryStats(0L, 0.0, 0.0, 0L, 0L, java.util.List.of()));

        mockMvc.perform(get("/api/system/query-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalQueries").value(0))
                .andExpect(jsonPath("$.avgResponseTimeMs").value(0.0))
                .andExpect(jsonPath("$.p95ResponseTimeMs").value(0.0))
                .andExpect(jsonPath("$.cacheHits").value(0))
                .andExpect(jsonPath("$.cacheMisses").value(0))
                .andExpect(jsonPath("$.recentQueries").isArray())
                .andExpect(jsonPath("$.recentQueries").isEmpty());
    }

    @Test
    void getQueryStatsReturnsPopulatedStats() throws Exception {
        var entry = new de.spraener.prjxp.mcp.QueryStatEntry("test query", 50L, 10, true);
        when(queryStatsCollector.getStats()).thenReturn(
                new QueryStats(5L, 45.0, 120.0, 3L, 2L, java.util.List.of(entry)));

        mockMvc.perform(get("/api/system/query-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalQueries").value(5))
                .andExpect(jsonPath("$.avgResponseTimeMs").value(45.0))
                .andExpect(jsonPath("$.p95ResponseTimeMs").value(120.0))
                .andExpect(jsonPath("$.cacheHits").value(3))
                .andExpect(jsonPath("$.cacheMisses").value(2))
                .andExpect(jsonPath("$.recentQueries.length()").value(1))
                .andExpect(jsonPath("$.recentQueries[0].query").value("test query"))
                .andExpect(jsonPath("$.recentQueries[0].responseTimeMs").value(50))
                .andExpect(jsonPath("$.recentQueries[0].resultCount").value(10))
                .andExpect(jsonPath("$.recentQueries[0].fromCache").value(true));
    }
}
