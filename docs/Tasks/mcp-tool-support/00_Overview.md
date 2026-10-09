# MCP Tool Support — Overview

## Ziel

Eine abstrahierte, erweiterbare Tool-Infrastruktur für docpipe und alle Module, die LLMs mit Tools ausstatten. Das System unterstützt:

1. **MCP-Clients** (bestehend, stdio/http)
2. **Groovy-Script-Tools** (neu, konfigurierbar via `application.yaml`)

## Architektur

```
┌─────────────────────────────────────────────────┐
│              ToolRegistry (neu)                   │
│  Single Source of Truth für alle Tools           │
│                                                   │
│  - registerMcpClient(McpClient)                  │
│  - loadGroovyTools(Path scriptDir)              │
│  - getToolDescriptions() → List<ToolDescription> │
│  - execute(String name, Map params) → String    │
└───────────────────┬─────────────────────────────┘
                    │
        ┌───────────┴───────────┐
        ▼                         ▼
McpClientManager          GroovyToolExecutor
(stellt MCP-Clients      (kompiliert .groovy-Files)
 zur Verfügung)           und registriert sie)
        │                         │
        └───────────┬───────────┘
                    ▼
            McPEnablingKIChatDecorator
            (fragt nur ToolRegistry ab)

McpProjectResolver (docpipe)
    (injiziert Tool-Beschreibungen aus ToolRegistry)
```

## Konfiguration

```yaml
mcp-servers:
  - name: "prjxp-hub"
    type: "http"
    url: "http://localhost:8080/mcp"
  - name: "FileTools"
    type: "groovy"
    scriptDir: "./groovyTools"   # default: ./groovyTools
```

## Groovy Tool Definition (Beispiel)

Jedes `.groovy`-File definiert **ein Tool**:

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
    def file = new File(projectRoot, params.path)
    if (!file.exists()) return "Error: File not found: ${params.path}"
    def content = file.text
    return "```java\n${content}\n```"
}

[name: name, description: description, parameters: parameters, execute: this.&execute]
```

## Sandboxed Context für Groovy-Scripte

| Variable | Typ | Beschreibung |
|----------|-----|-------------|
| `projectRoot` | `Path` | Projekt-Root (wo `application.yaml` liegt) |
| `baseDir` | `Path` | Working directory (kann je nach Kontext variieren) |
| `configSubset` | `ConfigSubset` | Read-only Subset der Config (keine Secrets) |
| `log` | `Logger` | SLF4J Logger für Debug-Ausgaben |

## Phasen

| Phase | Titel | Ziel |
|-------|-------|------|
| 01 | Core Abstractions | `ToolRegistry`, `ToolDefinition`, `GroovyToolExecutor` |
| 02 | Configuration | `McpServerReference` erweitern, `ConfigSubset` erstellen |
| 03 | Integration | `McpClientManager`, `McPEnablingKIChatDecorator` anpassen |
| 04 | Prompt Injection | `McpProjectResolver` dynamisch aus ToolRegistry |
| 05 | Example Tools | Sample Groovy-Tool-Definitionen + Tests |
