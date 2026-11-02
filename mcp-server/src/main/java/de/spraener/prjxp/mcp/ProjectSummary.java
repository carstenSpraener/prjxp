package de.spraener.prjxp.mcp;

/**
 * Lean view of a searchable project for the {@code listProjects} discovery tool:
 * the project name and the timestamp of its last index update.
 * <p>
 * {@code lastGeneratedAt} is the {@code generatedAt} watermark of the last live-update run
 * (epoch millis). It is {@code null} for projects without a live-update watermark:
 * SNAPSHOT projects, static-mode projects, and LIVE projects that have not run yet.
 * Keeping the name and the timestamp in separate fields lets an AI client use {@code name}
 * verbatim as the {@code project} argument of the search tools while still seeing how fresh
 * each index is.
 */
public record ProjectSummary(
    String name,
    Long lastGeneratedAt
) {}
