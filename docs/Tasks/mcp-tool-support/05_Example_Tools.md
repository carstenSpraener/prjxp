# Phase 05: Example Tools

> **STRICT EXECUTION RULES FOR THE AGENT:**
> 1. DO NOT run repository scans (`find_files`, `grep`, `search_code`, `list_dir`).
> 2. Read ONLY the files explicitly listed in the "File Scope" section below.
> 3. All required interfaces/DTOs are provided inline in this document. Do not fetch them from the codebase.
> 4. **MANDATORY FINAL STEP:** As soon as the implementation and tests (>= 80% coverage) are green, execute `/compact` IMMEDIATELY to clean up the context window before returning control.

## 1. Target & Scope
* **Objective:** Create sample Groovy tool definitions that demonstrate the system and serve as documentation for users.
* **Target Files to Create:**
    * Create: `prjxp-common/src/main/resources/groovyTools/fileRead.groovy`
    * Create: `prjxp-common/src/main/resources/groovyTools/dirList.groovy`
    * Create: `prjxp-common/src/main/resources/groovyTools/TEMPLATE.groovy` (example template)
    * Create: `prjxp-common/src/test/java/de/spraener/prjxp/common/toolregistry/GroovyToolIntegrationTest.java`

## 2. Inline Required Context (Contracts & Signatures)

### `fileRead.groovy`
```groovy
def name = "fileRead"
def description = """
    Read the full content of a source file.
    Use this when you need to examine specific files in detail,
    especially after reviewing source code skeletons.
"""
def parameters = [
    path: "Path to the file (relative to project root or absolute)"
]

def execute(params) {
    def path = params.path
    if (!path) return "Error: No path provided"

    def file = new File(projectRoot, path)
    if (!file.exists()) return "Error: File not found: ${path}"
    if (!file.isFile()) return "Error: Not a file: ${path}"

    def content = file.text
    // Detect language from extension for code block formatting
    def ext = path.contains('.') ? path.substring(path.lastIndexOf('.')) : ''
    return "```${ext}\n${content}\n```"
}

[name: name, description: description, parameters: parameters, execute: this.&execute]
```

### `dirList.groovy`
```groovy
def name = "dirList"
def description = """
    List files and directories in a given path.
    Useful for exploring project structure and finding relevant files to read.
"""
def parameters = [
    path: "Directory path (relative to project root or absolute). Use '.' for the project root."
]

def execute(params) {
    def path = params.path ?: '.'
    def dir = new File(projectRoot, path)

    if (!dir.exists()) return "Error: Directory not found: ${path}"
    if (!dir.isDirectory()) return "Error: Not a directory: ${path}"

    def entries = dir.listFiles()?.sort { it.name } ?: []
    if (entries.isEmpty()) return "Directory '${path}' is empty."

    StringBuilder sb = new StringBuilder()
    sb.append("Contents of '${path}':\n")
    for (entry in entries) {
        def prefix = entry.isDirectory() ? "[DIR]  " : "       "
        sb.append(prefix).append(entry.name).append("\n")
    }
    return sb.toString()
}

[name: name, description: description, parameters: parameters, execute: this.&execute]
```

### `TEMPLATE.groovy` (Example for users)
```groovy
// === TOOL NAME & DESCRIPTION ===
def name = "myTool"          // Unique tool name (used by LLM to call this tool)
def description = """
    Describe what this tool does and when the LLM should use it.
    Be specific about the use case and any limitations.
"""

// === PARAMETERS ===
def parameters = [
    param1: "Description of parameter 1",
    param2: "Description of parameter 2 (optional)"
]

// === EXECUTION LOGIC ===
def execute(params) {
    // Access sandboxed context variables:
    // - projectRoot (Path): The project root directory
    // - baseDir (Path): Working directory
    // - configSubset (ConfigSubset): Safe subset of configuration
    // - log (Logger): SLF4J logger for debug output

    def result = "Your implementation here"
    return result   // Must return a String
}

// === RETURN THE TOOL DEFINITION ===
[name: name, description: description, parameters: parameters, execute: this.&execute]
```

## 3. Test Requirements (TDD)

### `GroovyToolIntegrationTest.java`
- **fileRead exists and works**: Verify that the fileRead tool can be loaded from resources and executed
- **dirList exists and works**: Verify that the dirList tool can be loaded from resources and executed
- **TEMPLATE.groovy is loadable**: Verify that the template file can be compiled without errors
- **Sandboxed context is available**: Verify that `projectRoot`, `baseDir` and `log` are accessible in the execute closure

## 4. Dependencies
* `GroovyToolExecutor`, `ToolRegistry` (from Phase 01)
* `ConfigSubset` (from Phase 02)
