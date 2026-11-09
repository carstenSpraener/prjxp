package de.spraener.prjxp.mcp.hub.github;

/**
 * The outcome of an async GitHub operation (import by URL, or update by project name), exposed to
 * the WebUI for visual feedback. {@code key} is the trimmed URL for imports and the project name
 * for updates — the client polls until its key is no longer PENDING.
 */
public record GithubOperation(String key, String status, String message, long timestamp) {
    public static final String PENDING = "PENDING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
}
