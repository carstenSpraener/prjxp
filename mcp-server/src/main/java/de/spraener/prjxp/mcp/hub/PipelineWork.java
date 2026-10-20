package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.common.config.ProjectDefinition;

/**
 * The work a retrigger run executes. Root runs (work == null) execute the standard or
 * pre-embedded pipeline; retrigger runs execute their work instead.
 */
@FunctionalInterface
public interface PipelineWork {
    void execute(String projectName, ProjectDefinition definition) throws Exception;
}
