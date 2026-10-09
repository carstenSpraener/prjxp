package de.spraener.prjxp.common.config;

import lombok.Value;
import java.util.List;

/**
 * Safe, read-only subset of PrjXPConfig for sandboxed Groovy tool execution.
 * Excludes secrets, API keys and other sensitive data. Only exposes structural/project info.
 */
@Value
public class ConfigSubset {
    /** Active project name (if configured). */
    String activeProjectName;

    /** List of available project names. */
    List<String> projectNames;

    /** Chat model stereotypes (names only, no API keys). */
    List<String> chatModelStereotypes;

    /** MCP server names (for reference, no URLs/commands). */
    List<String> mcpServerNames;

    /** Embedding store type (e.g., "LUCENE"). */
    String embeddingStoreType;

    /** Lucene index path (read-only, for file lookups). */
    String luceneIndexPath;

    /** Vector dimension (for validation). */
    int vectorDimension;
}
