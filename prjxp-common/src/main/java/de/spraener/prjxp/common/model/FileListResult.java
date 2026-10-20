package de.spraener.prjxp.common.model;

import java.util.List;

/**
 * Result of a structural file listing: distinct file paths from the index matching a pattern.
 * Orthogonal to the content search ladder (vectorSearch -> grep -> read): it answers
 * "which files exist?", not "what do they say?".
 */
public record FileListResult(
        List<String> files,    // distinct paths, sorted
        boolean truncated) {   // more matches exist beyond the limit
}
