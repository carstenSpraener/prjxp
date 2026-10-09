export interface SystemHealth {
  cpuUsage: number;        // 0.0-1.0
  totalMemory: number;     // bytes
  usedMemory: number;      // bytes
  freeDiskSpace: number;   // bytes
  totalDiskSpace: number;  // bytes
  activeConnections: number;
}
