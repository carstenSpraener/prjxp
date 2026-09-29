package de.spraener.prjxp.mcp;

public record ByIndexQuery(
        String language,
        String fqn,
        String symbolType,
        String methodName,
        String signatureHash,
        String containerFqn,
        String project,
        int limit) {
}
