package de.spraener.prjxp.mcp.hub;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

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
    /**
     * Directory names (case-insensitive, matched against the bare name, not the path) that the live-project
     * marker scan never descends into. Defaults cover common VCS-internal folders. Add heavy non-project
     * subtrees here (e.g. old SVN {@code branches}/{@code tags} checkouts) to keep the recursive scan fast —
     * without this, every poll must fully walk such trees before reaching a marker that sits alphabetically
     * "after" them (e.g. under {@code trunk}), which can make discovery of that project take minutes on slow
     * (e.g. Windows Docker bind-mounted) volumes.
     */
    private Set<String> scanExcludeDirNames = new LinkedHashSet<>(Set.of(".git", ".svn", ".hg", "node_modules"));
    /**
     * Maximum recursion depth (in directory levels below {@code importDir}) the live-project marker scan
     * descends into before giving up on that branch — default 8. Bounds the worst case when {@code importDir}
     * is mounted broadly (e.g. an entire "projects" drive with many unrelated, deeply nested repositories):
     * without a cap, one huge sibling tree with no marker at all forces a full recursive walk on every poll.
     * Increase this if a real project's {@code prjxp.yaml} sits deeper than 8 levels below {@code importDir}.
     */
    private int scanMaxDepth = 8;
}
