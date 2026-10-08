# DocPipe MCP-Tool — Praxisbeispiel

Dieses Dokument zeigt Schritt für Schritt, wie man docpipe mit MCP-Tools einsetzt, um
dokumentation aus einem eingebetteten Projekt zu generieren.

---

## Szenario: Architektur-Dokumentation für ein Spring-Projekt

**Ziel:** Erzeugen einer `architecture-overview.md` aus einem eingebetteten Projekt, indem
der LLM autonom über den prjxp MCP-Server nach Informationen sucht.

---

## Schritt 1: Projekt einbetten (chunk → embed)

Zuerst muss das Ziel-Projekt im prjxp-Index vorhanden sein.

```bash
# Chunking
./gradlew :chunk-norris:run --args="--project.dir=/path/to/my-spring-app"

# Embedding
./gradlew :tibed:run --args="--project.dir=/path/to/my-spring-app"
```

Oder über den Docker-Hub:

```bash
docker compose up -d hub
tar czf import/my-spring-app.tar my-spring-app/
# → automatisch chunking + embedding, Status wird READY
```

---

## Schritt 2: MCP-Server starten

Der prjxp MCP-Server muss laufen, damit docpipe's LLM darauf zugreifen kann.

```bash
# Lokaler Start (Port 7007)
./gradlew :mcp-server:run

# Oder via Docker
docker compose up -d mcp-server
```

Verifikation:

```bash
curl http://localhost:7007/prjxp/tools/ping
# → "pong!"

curl http://localhost:7007/prjxp/tools/projects
# → Liste der eingebetteten Projekte (Status: READY)
```

---

## Schritt 3: docpipe konfigurieren

### 3a. `application.yaml` — MCP-Server definieren

```yaml
prjxp:
  # ... (embedding, chatModels etc.)

  mcp-servers:
    - name: "prjxp-search"
      type: "http"
      url: "${PRJXP_MCP_URL:http://localhost:7007/mcp}"
      defaultProject: "my-spring-app"

  docpipe:
    maxthreads: 5
```

**Erläuterung:**

| Feld | Bedeutung |
|------|-----------|
| `type: "http"` | Streamable HTTP-Transport (nicht stdio) |
| `url` | URL des MCP-Servers (`/mcp`-Endpoint) |
| `defaultProject` | Name des eingebetteten Projekts — wird im System-Prompt an den LLM übergeben |

### 3b. `.dp/`-Verzeichnis erstellen

Im Projekt-Root (oder einem Unterverzeichnis) eine `.dp/`-Struktur anlegen:

```
my-spring-app/
├── .dp/
│   ├── documents.json          # Job-Definitionen
│   ├── models.json             # LLM-Modelle (Stereotype)
│   └── architecture-overview.hbs  # Handlebars-Prompt-Template
```

### 3c. `documents.json` — Job definieren

```json
{
  "contentCreations": [
    {
      "outputFile": "docs/architecture-overview.md",
      "stereotype": "architect",
      "promptTemplate": "architecture-overview.hbs"
    }
  ]
}
```

### 3d. `models.json` — LLM-Modell definieren

```json
{
  "chatModels": [
    {
      "stereoType": "architect",
      "serverType": "ollama",
      "modelName": "qwen3.6-27b",
      "apiBaseURL": "http://localhost:11434"
    }
  ]
}
```

### 3e. `architecture-overview.hbs` — Prompt-Template

```handlebars
{{mcp-project project="my-spring-app"}}

Erstelle eine Architektur-Dokumentation für das Projekt "my-spring-app".

Nutze die verfügbaren Suchwerkzeuge (vectorSearch, grep, readFile), um Informationen
über folgende Aspekte zu sammeln:

1. **Module & Pakete:** Welche Hauptmodule und Packages gibt es?
2. **Komponenten:** Welche Spring-Beans, Controller, Services und Repositories existieren?
3. **Datenmodelle:** Welche Entitäten/DTOs werden verwendet?
4. **Konfiguration:** Wie ist das Projekt konfiguriert (application.yaml, profiles)?
5. **Abhängigkeiten:** Welche externen Bibliotheken werden verwendet?

Strukturiere die Dokumentation mit Überschriften, Tabellen und Code-Beispielen.
Füge für jedes Hauptmodul eine kurze Beschreibung hinzu.
```

**Wichtig:** `{{mcp-project project="my-spring-app"}}` injiziert den Text:

> *Search the embedded project 'my-spring-app' using available MCP tools (vectorSearch, grep, readFile, readBySignature). Use these tools to gather information about the project's structure, modules, and key classes before answering.*

Dies signalisiert dem LLM, dass es MCP-Tools zur Verfügung stehen.

---

## Schritt 4: docpipe ausführen

```bash
./gradlew :docpipe:run --args="--project.dir=/path/to/my-spring-app"
```

**Was passiert intern:**

1. `DocPipeRunner` entdeckt `.dp/documents.json` → erstellt `ContentCreationTask`
2. `PromptResolvingService` kompiliert `architecture-overview.hbs`:
   - `{{mcp-project project="my-spring-app"}}` → injiziert Projekt-Kontext
3. `LLMService.chat()` ruft `KIChatProvider.getByStereotype("architect")` auf
4. `McpClientManager.decorate()` wrappt den LLM in `McPEnablingKIChatDecorator`
5. Der Decorator erstellt einen LangChain4j `AiServices` Agent mit:
   - **System-Prompt:** "You have access to MCP tools... The active project is: 'my-spring-app'..."
   - **MCP-Tools:** `vectorSearch`, `grep`, `readFile`, `readBySignature` (via HTTP)
6. Der LLM entscheidet autonom:
   - Ruft `vectorSearch("What are the main modules?")` → erhält Skeleton-Übersicht
   - Ruft `grep("public class *Controller")` → findet alle Controller-Klassen
   - Ruft `readFile("src/main/java/.../Application.java")` → liest Hauptklasse
   - Generiert finale Antwort basierend auf gesammelten Informationen
7. Ergebnis wird in `docs/architecture-overview.md` geschrieben

---

## Schritt 5: Ergebnis prüfen

```bash
cat docs/architecture-overview.md
```

Beispielausgabe:

```markdown
# Architektur-Übersicht: my-spring-app

## Module & Pakete

Das Projekt besteht aus folgenden Hauptmodulen:
| Modul | Beschreibung |
|-------|-------------|
| `com.example.app.controller` | REST-Controller für API-Endpunkte |
| `com.example.app.service` | Business-Logik-Services |
| ...

## Hauptkomponenten

### Controller
- `UserController` — CRUD für Benutzer (`/api/users`)
- `ProductController` — Produktverwaltung (`/api/products`)

### Services
...
```

---

## Fortgeschrittene Beispiele

### Beispiel A: Mehrere Dokumente in einem Job

```json
{
  "contentCreations": [
    {
      "outputFile": "docs/api-reference.md",
      "stereotype": "architect",
      "promptTemplate": "api-reference.hbs"
    },
    {
      "outputFile": "docs/data-model.md",
      "stereotype": "architect",
      "promptTemplate": "data-model.hbs"
    },
    {
      "outputFile": "docs/dependencies.md",
      "stereotype": "architect",
      "promptTemplate": "dependencies.hbs"
    }
  ]
}
```

### Beispiel B: `forEach` für per-Datei-Dokumentation

```json
{
  "contentCreations": [
    {
      "outputFile": "docs/class-docs/{fileName}.md",
      "stereotype": "javadoc",
      "promptTemplate": "class-doc.hbs",
      "forEach": "src/main/java/**/*Controller.java"
    }
  ]
}
```

Dies erzeugt für jeden Controller eine separate Klassendokumentation.

### Beispiel C: Kombination mit `gr`-Resolver (RAG + MCP)

```handlebars
{{mcp-project project="my-spring-app"}}

{{#gr prj="my-spring-app"}}
Erstelle eine Dokumentation basierend auf dem folgenden Kontext:
{{/gr}}

Ergänze die Informationen durch eigene MCP-Tool-Suchen, falls Lücken bestehen.
```

Hier wird zuerst `GRPromptEnrichment` (RAG) verwendet, dann kann der LLM
zusätzlich MCP-Tools für vertiefende Recherchen nutzen.

---

## Troubleshooting

| Problem | Lösung |
|---------|--------|
| LLM ruft keine MCP-Tools auf | Prüfe, ob `mcp-servers` in `application.yaml` korrekt konfiguriert sind; überprüfe, ob MCP-Server auf `:7007/mcp` erreichbar ist |
| `defaultProject` nicht im System-Prompt | Stelle sicher, dass `defaultProject` in der MCP-Server-Konfiguration gesetzt ist (nicht leer/null) |
| `{{mcp-project}}` gibt leeren String zurück | Prüfe, ob der `project`-Parameter korrekt übergeben wird: `{{mcp-project project="name"}}` |
| MCP-Server nicht erreichbar | `curl http://localhost:7007/prjxp/tools/ping` → sollte `"pong!"` zurückgeben |
| LLM antwortet ohne Tools zu nutzen | Der LLM muss Tool-Calling unterstützen; einige kleinere Modelle ignorieren Tool-Definitionen — verwende ein Modell mit guter Tool-Support (z.B. Qwen 3, Llama 4) |

---

## Konfigurations-Referenz

### `application.yaml` — MCP-Server-Konfiguration

```yaml
prjxp:
  mcp-servers:
    - name: "prjxp-search"       # Name (intern)
      type: "http"                # Transport-Typ ("stdio" oder "http")
      url: "http://localhost:7007/mcp"  # MCP-Server-URL
      defaultProject: "my-spring-app"    # Projektname für System-Prompt (optional)
```

### `documents.json` — Job-Definition

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|-------------|
| `outputFile` | String | Ja | Ausgabepfad (relativ zu project.dir) |
| `stereotype` | String | Ja | Modell-Stereotyp (muss in `models.json` existieren) |
| `promptTemplate` | String | Ja | Handlebars-Template-Datei (in `.dp/`) |
| `filterList` | String[] | Nein | Post-Processing-Filter (z.B. `"noSurroundingCodeBlock"`) |
| `ps` | String | Nein | Post-Script (wird an das Ergebnis angehängt) |
| `args` | Map | Nein | Kontext-Argumente für das Template (z.B. `currentFile`) |
| `forEach` | String | Nein | Glob-Pattern für per-Datei-Jobs |

### `models.json` — Modell-Konfiguration

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|-------------|
| `stereoType` | String | Ja | Stereotyp-Name (z.B. `"architect"`, `"javadoc"`) |
| `serverType` | String | Ja | Provider (`ollama`, `openai`, `gemini`, `lmstudio`, `azure`) |
| `modelName` | String | Ja | Modell-Name (z.B. `"qwen3.6-27b"`) |
| `apiBaseURL` | String | Nein | API-Endpoint (provider-spezifisch) |
| `apiKey` | String | Nein | API-Schlüssel (oder `${ENV_VAR}`) |

### Handlebars-Helper — `mcp-project`

| Syntax | Beschreibung |
|--------|-------------|
| `{{mcp-project project="my-spring-app"}}` | Injiziert Projekt-Kontext mit Tool-Hinweis |
| `{{mcpProject project="my-spring-app"}}` | CamelCase-Alias (identisch) |
| `{{mcp-project}}` | Ohne Parameter → generischer Hinweis (kein Projektname) |

---

## Siehe auch

- [docpipe/doc/Configuration.md](./Configuration.md) — Vollständige docpipe-Konfiguration
- [docpipe/doc/HowTo.md](./HowTo.md) — Allgemeine How-To-Anleitungen
- [docpipe/doc/concepts/tool-enabled-llm.md](./concepts/tool-enabled-llm.md) — Konzept & Architektur
- [arc42/08_cross_cutting_concepts.md](../../doc/arc42/08_cross_cutting_concepts.md) — § 8.9 MCP Client Infrastructure
