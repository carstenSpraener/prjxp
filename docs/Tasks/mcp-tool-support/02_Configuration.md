# Phase 02: Configuration

> **STRICT EXECUTION RULES FOR THE AGENT:**
> 1. DO NOT run repository scans (`find_files`, `grep`, `search_code`, `list_dir`).
> 2. Read ONLY the files explicitly listed in the "File Scope" section below.
> 3. All required interfaces/DTOs are provided inline in this document. Do not fetch them from the codebase.
> 4. **MANDATORY FINAL STEP:** As soon as the implementation and tests (>= 80% coverage) are green, execute `/compact` IMMEDIATELY to clean up the context window before returning control.

## 1. Target & Scope
* **Objective:** Extend `McpServerReference` to support Groovy server type and create a safe `ConfigSubset` for sandboxed Groovy execution.
* **Target Files to Create/Modify:**
    * Modify: `prjxp-common/src/main/java/de/spraener/prjxp/common/config/McpServerReference.java`
    * Create: `prjxp-common/src/main/java/de/spraener/prjxp/common/config/ConfigSubset.java`
    * Modify: `prjxp-common/src/main/java/de/spraener/prjxp/common/config/PrjXPConfig.java` (add method to create ConfigSubset)
    * Create: `prjxp-common/src/test/java/de/spraener/prjxp/common/config/ConfigSubsetTest.java`

## 2. Inline Required Context (Contracts & Signatures)

### `McpServerReference.java` (MODIFY)
Add new fields:
```java
@Data
public class McpServerReference {
    private String name;
    private String type; // "stdio", "http" or "groovy" (NEU)
    private String command;
    private List<String> args;
    private String url;
    private String defaultProject;

    // NEU: Groovy-specific fields
    /** Directory containing .groovy tool definition files. Default: "./groovyTools" (relative to project root). */
    private String scriptDir;
}
```

### `ConfigSubset.java` (NEU)
```java
package de.spraener.prjxp.common.config;

import lombok.Value;
import java.util.List;
import java.util.Map;

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
```

### `PrjXPConfig.java` (MODIFY)
Add a method to create the safe subset:
```java
/** Create a safe, read-only subset of this config for sandboxed Groovy execution. */
public ConfigSubset toConfigSubset() {
    return new ConfigSubset(
            getActiveProjectName(),
            getProjects().stream().map(ProjectDefinition::getName).toList(),
            getChatModels() != null ? getChatModels().stream()
                    .map(PrjXPChatModelReference::getStereoType).toList() : List.of(),
            getMcpServers() != null ? getMcpServers().stream()
                    .map(McpServerReference::getName).toList() : List.of(),
            getEmbeddingStoreType() != null ? getEmbeddingStoreType().name() : "LUCENE",
            getEmbeddingStoreLucene() != null ? getEmbeddingStoreLucene().getIndexPath() : ".prjxp-data/lucene-index",
            getEmbeddingStoreLucene() != null ? getEmbeddingStoreLucene().getVectorDimension() : 1024
    );
}
```

## 3. Test Requirements (TDD)

### `ConfigSubsetTest.java`
- **toConfigSubset includes project names**: Verify that project names are included in the subset
- **toConfigSubset excludes sensitive data**: Verify that API keys, URLs and commands are NOT in the subset
- **ConfigSubset is immutable**: Verify that the returned object cannot be modified (lombok `@Value`)
- **toConfigSubset handles nulls**: Verify that missing config sections don't cause NPE

## 4. Dependencies
* `lombok` (for `@Value`)
* Existing: `PrjXPConfig`, `ProjectDefinition`, `PrjXPChatModelReference`, `McpServerReference`
