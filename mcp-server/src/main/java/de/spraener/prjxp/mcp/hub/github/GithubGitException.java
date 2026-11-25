package de.spraener.prjxp.mcp.hub.github;

/** A git process failed (clone/pull): bad URL, network error, non-zero exit, or timeout. */
public class GithubGitException extends Exception {
    public GithubGitException(String message) { super(message); }
    public GithubGitException(String message, Throwable cause) { super(message, cause); }
}
