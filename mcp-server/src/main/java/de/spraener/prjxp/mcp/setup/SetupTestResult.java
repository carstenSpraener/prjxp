package de.spraener.prjxp.mcp.setup;
public record SetupTestResult(boolean success, String message, long latencyMs, String response,
                               Integer dimensions, String testId, String configVersion) {}
