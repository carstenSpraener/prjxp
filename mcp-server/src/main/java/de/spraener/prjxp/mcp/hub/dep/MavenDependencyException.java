package de.spraener.prjxp.mcp.hub.dep;

/** Checked failure during Maven resolution/download (network, 404, size, parse). */
public class MavenDependencyException extends Exception {
    public MavenDependencyException(String message) { super(message); }
    public MavenDependencyException(String message, Throwable cause) { super(message, cause); }
}
