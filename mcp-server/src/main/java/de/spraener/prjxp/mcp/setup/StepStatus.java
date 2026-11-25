package de.spraener.prjxp.mcp.setup;
public record StepStatus(boolean configured, boolean tested, boolean required, boolean skipped,
                         String problemCode, String message, String testedAt) {}
