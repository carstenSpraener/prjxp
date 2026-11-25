package de.spraener.prjxp.mcp.setup;
public record SetupStatus(String configVersion, boolean completed, String completionMode,
                          boolean restartRequired, String resumeStep, StepStatus embedding,
                          StepStatus github, StepStatus chat, StepStatus optimization) {}
