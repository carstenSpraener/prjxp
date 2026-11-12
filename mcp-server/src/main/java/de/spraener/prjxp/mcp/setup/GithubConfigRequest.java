package de.spraener.prjxp.mcp.setup;
public record GithubConfigRequest(String tokenKeyEnv, String defaultPat) {
    @Override public String toString() { return "GithubConfigRequest[redacted]"; }
}
