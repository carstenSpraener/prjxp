package de.spraener.prjxp.mcp.hub;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "prjxp.hub")
public class HubProperties {
    /** Master switch for all hub beans. */
    private boolean enabled = false;
    /** Directory watched for tar uploads (default /import). */
    private String importDir = "/import";
    /** Root directory extracted projects live in (default /projects). */
    private String projectsRoot = "/projects";
    /** Hub-managed area for JSONL output of live projects (default /data/chunks) — the hub never writes into the live tree. */
    private String liveJsonlDir = "/data/chunks";
    /** Poller interval in milliseconds (default 5000). */
    private long pollIntervalMs = 5_000;
    /** Max tar file size in bytes (default 2 GiB). */
    private long maxTarBytes = 2L * 1024 * 1024 * 1024;
    /** Max total extracted size in bytes (default 4 GiB, compression-bomb guard). */
    private long maxExtractedBytes = 4L * 1024 * 1024 * 1024;
    /** Max number of tar entries (default 100_000). */
    private int maxEntries = 100_000;
}
