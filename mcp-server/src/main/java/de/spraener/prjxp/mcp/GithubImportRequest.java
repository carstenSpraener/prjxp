package de.spraener.prjxp.mcp;

/**
 * Request body for {@code POST /api/projects/import-github}: the git URL of the repository to import,
 * and an optional personal access token for private repositories (encrypted at rest, never persisted
 * in plaintext). {@code token} is nullable/blank for public repos.
 */
public record GithubImportRequest(String url, String token) {}
