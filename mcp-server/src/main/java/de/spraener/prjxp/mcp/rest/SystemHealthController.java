package de.spraener.prjxp.mcp.rest;

import de.spraener.prjxp.mcp.QueryStats;
import de.spraener.prjxp.mcp.SystemHealth;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;

@RestController
@RequestMapping("/api/system")
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SystemHealthController {

    private final QueryStatsCollector queryStatsCollector;

    @GetMapping("/health")
    public SystemHealth getSystemHealth() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();

        File rootDir = new File(System.getProperty("user.dir"));
        long freeDiskSpace = rootDir.getFreeSpace();
        long totalDiskSpace = rootDir.getTotalSpace();

        // CPU usage: simplified approximation (0.5 as placeholder)
        double cpuUsage = 0.5;

        return new SystemHealth(
            cpuUsage,
            totalMemory,
            totalMemory - freeMemory,
            freeDiskSpace,
            totalDiskSpace,
            0 // TODO: track active connections via Servlet filter
        );
    }

    @GetMapping("/query-stats")
    public QueryStats getQueryStats() {
        return queryStatsCollector.getStats();
    }
}
