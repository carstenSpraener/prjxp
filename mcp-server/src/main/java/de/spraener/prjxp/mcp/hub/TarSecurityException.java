package de.spraener.prjxp.mcp.hub;

/** Thrown when a tar entry violates the import security policy (zip-slip, links, limits). */
public class TarSecurityException extends RuntimeException {
    public TarSecurityException(String message) { super(message); }
}
