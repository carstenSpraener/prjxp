package de.spraener.prjxp.mcp.hub.github;

public record GithubUserResult(boolean valid, String login, String message, long latencyMs) {}
