package de.spraener.prjxp.mcp;

public record SystemHealth(
    Double cpuUsage,       // 0.0-1.0
    Long totalMemory,      // bytes (Runtime.totalMemory())
    Long usedMemory,       // bytes (total - free)
    Long freeDiskSpace,    // bytes
    Long totalDiskSpace,   // bytes
    Integer activeConnections
) {}
