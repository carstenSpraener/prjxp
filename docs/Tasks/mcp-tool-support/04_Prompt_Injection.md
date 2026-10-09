# Phase 04: Prompt Injection

> **STRICT EXECUTION RULES FOR THE AGENT:**
> 1. DO NOT run repository scans (`find_files`, `grep`, `search_code`, `list_dir`).
> 2. Read ONLY the files explicitly listed in the "File Scope" section below.
> 3. All required interfaces/DTOs are provided inline in this document. Do not fetch them from the codebase.
> 4. **MANDATORY FINAL STEP:** As soon as the implementation and tests (>= 80% coverage) are green, execute `/compact` IMMEDIATELY to clean up the context window before returning control.

## 1. Target & Scope
* **Objective:** Adapt `McpProjectResolver` in docpipe to dynamically inject tool descriptions from `ToolRegistry` instead of hardcoded text.
* **Target Files to Create/Modify:**
    * Modify: `docpipe/src/main/java/de/spraener/prjxp/docpipe/prompt/McpProjectResolver.java`
    * Create: `docpipe/src/test/java/de/spraener/prjxp/docpipe/prompt/McpProjectResolverTest.java`

## 2. Inline Required Context (Contracts & Signatures)

### `McpProjectResolver.java` (MODIFY)
**Current state:** Hardcoded tool descriptions in `resolve()`:
```java
// Current: hardcoded text
return "Search the embedded project 'prjxp' using available MCP tools (vectorSearch, grep, readFile, readBySignature)...";
```

**New state:** Dynamic tool descriptions from `ToolRegistry`:
```java
@Component
@RequiredArgsConstructor
public class McpProjectResolver implements TemplateResolver {

    private final ToolRegistry toolRegistry;   // NEU: dependency injection

    @Override
    public String getID() { return "mcp-project"; }

    @Override
    public List<String> getAliases() { return List.of("mcpProject"); }

    @Override
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        String project = options.hash("project", null);

        // Get dynamic tool descriptions from ToolRegistry
        List<String> toolDescriptions = toolRegistry.getToolDescriptions();

        if (toolDescriptions.isEmpty()) {
            return "";   // No tools available — inject nothing
        }

        StringBuilder sb = new StringBuilder();
        sb.append("You have access to the following tools:\n\n");
        for (String desc : toolDescriptions) {
            sb.append(desc).append("\n\n");
        }

        if (project != null && !project.isBlank()) {
            sb.append("The active project is: '").append(project).append("'.\n\n");
            sb.append("Use these tools to gather information about the project's structure, modules, and key classes before answering.");
        } else {
            sb.append("Use these tools to gather information before answering the user's question.");
        }

        return sb.toString();
    }
}
```

## 3. Test Requirements (TDD)

### `McpProjectResolverTest.java`
- **resolve with project param**: Verify that the output includes tool descriptions and the active project name
- **resolve without project param**: Verify that the output includes tool descriptions but no project-specific text
- **resolve with empty registry**: Verify that an empty string is returned when no tools are registered
- **getID and getAliases**: Verify the helper name and aliases

## 4. Dependencies
* `ToolRegistry` (from Phase 01)
* Existing: `McpProjectResolver`, `TemplateResolver`
