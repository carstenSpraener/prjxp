package de.spraener.prjxp.mcp.setup;
public record ChatModelRequest(String stereoType, String serverType, String modelName, String apiKey,
                               String providerUrl, Integer timeoutSecs, Double temperature) {
    @Override public String toString() { return "ChatModelRequest[redacted]"; }
}
