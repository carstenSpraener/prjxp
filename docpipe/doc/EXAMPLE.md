# DocPipe MCP-Tool — Praxisbeispiel

Dieses Dokument zeigt Schritt für Schritt, wie man docpipe mit MCP-Tools einsetzt, um
Dokumentation aus einem eingebetteten Projekt zu generieren.

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

### 3a. `application.yaml` — MCP-Server & Modelle definieren

```yaml
prjxp:
  hub:
    enabled: true
    importDir: "."
    projectsRoot: "/path/to/.prjxp/projects"
    liveJsonlDir: "/path/to/.prjxp/data/chunks"

  embeddingStoreLucene:
    indexPath: ${LUCENE_INDEX_PATH:.prjxp-data/lucene-index}
    vectorDimension: ${LUCENE_VECTOR_DIMENSION:1024}
    name: lucene

  mcp-servers:
    - name: "prjxp-search"
      type: "http"
      url: "http://localhost:7007/mcp"
      defaultProject: "my-spring-app"

  chatModels:
    - stereoType: documentation
      serverType: lm-studio
      modelName: qwen3.6-27b
      providerUrl: http://localhost:1234/v1
      apiKey: ${LM_STUDIO_API_KEY}
      temperature: 0.5
      timeoutSecs: 120

  embedding:
    type: "OPEN_AI"
    apiBaseURL: http://localhost:1234/v1
    modelName: mxbai-embed-large-v1
    timeout: 20
    apiKey: ${LM_STUDIO_API_KEY}

  embeddingStores:
    - projectName: cwd
      isDefault: true

mcp:
  cors:
    allowed-patterns: "http://localhost:*"

server:
  port: ${SERVER_PORT:7007}

spring:
  main:
    allow-bean-definition-overriding: true
```

**Erläuterung:**

| Feld | Bedeutung |
|------|-----------|
| `mcp-servers[].type: "http"` | Streamable HTTP-Transport (nicht stdio) |
| `mcp-servers[].url` | URL des MCP-Servers (`/mcp`-Endpoint) |
| `mcp-servers[].defaultProject` | Name des eingebetteten Projekts — wird im System-Prompt an den LLM übergeben |
| `chatModels[].stereoType` | Stereotyp-Name (wird in documents.json referenziert) |
| `chatModels[].serverType` | Provider (`ollama`, `lm-studio`, `openai`, `gemini`, `azure`) |
| `chatModels[].providerUrl` | API-Endpoint des Providers |

### 3b. `.dp/`-Verzeichnis erstellen

Im Projekt-Root (oder einem Unterverzeichnis) eine `.dp/`-Struktur anlegen:

```
my-spring-app/
├── .dp/
│   ├── documents.json          # Job-Definitionen (Array)
│   └── architecture-overview.hbs  # Handlebars-Prompt-Template
```

### 3c. `documents.json` — Job definieren (Array)

**Wichtig:** `documents.json` ist ein **Array von Objekten**, nicht ein Objekt mit einem
`contentCreations`-Feld.

```json
[
  {
    "outputFile": "docs/architecture-overview.md",
    "stereotype": "documentation",
    "prompt": "architecture-overview.hbs"
  }
]
```

### 3d. `architecture-overview.hbs` — Prompt-Template

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

1. `DocPipeRunner` entdeckt `.dp/documents.json` → erstellt `ContentCreationTask`(s)
2. `PromptResolvingService` kompiliert `architecture-overview.hbs`:
   - `{{mcp-project project="my-spring-app"}}` → injiziert Projekt-Kontext
3. `LLMService.chat()` ruft `KIChatProvider.getByStereotype("documentation")` auf
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
[
  {
    "outputFile": "docs/api-reference.md",
    "stereotype": "documentation",
    "prompt": "api-reference.hbs"
  },
  {
    "outputFile": "docs/data-model.md",
    "stereotype": "documentation",
    "prompt": "data-model.hbs"
  },
  {
    "outputFile": "docs/dependencies.md",
    "stereotype": "documentation",
    "prompt": "dependencies.hbs"
  }
]
```

### Beispiel B: `forEach` für per-Datei-Dokumentation

```json
[
  {
    "outputFile": "docs/class-docs/{fileName}.md",
    "stereotype": "documentation",
    "prompt": "class-doc.hbs",
    "forEach": "src/main/java/**/*Controller.java"
  }
]
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

### Beispiel D: `args` im Template verwenden

```json
[
  {
    "outputFile": "docs/custom-doc.md",
    "stereotype": "documentation",
    "prompt": "custom-doc.hbs",
    "args": {
      "focusArea": "security"
    }
  }
]
```

Template (`custom-doc.hbs`):
```handlebars
{{mcp-project project="my-spring-app"}}

Erstelle eine Dokumentation mit Fokus auf "{{args.focusArea}}".
```

---

## Troubleshooting

| Problem | Lösung |
|---------|--------|
| LLM ruft keine MCP-Tools auf | Prüfe, ob `mcp-servers` in `application.yaml` korrekt konfiguriert sind; überprüfe, ob MCP-Server auf `:7007/mcp` erreichbar ist |
| `defaultProject` nicht im System-Prompt | Stelle sicher, dass `defaultProject` in der MCP-Server-Konfiguration gesetzt ist (nicht leer/null) |
| `{{mcp-project}}` gibt leeren String zurück | Prüfe, ob der `project`-Parameter korrekt übergeben wird: `{{mcp-project project="name"}}` |
| MCP-Server nicht erreichbar | `curl http://localhost:7007/prjxp/tools/ping` → sollte `"pong!"` zurückgeben |
| LLM antwortet ohne Tools zu nutzen | Der LLM muss Tool-Calling unterstützen; einige kleinere Modelle ignorieren Tool-Definitionen — verwende ein Modell mit guter Tool-Support (z.B. Qwen 3, Llama 4) |
| `documents.json` wird nicht gefunden | Stelle sicher, dass die Datei im `.dp/`-Verzeichnis liegt und ein gültiges JSON-**Array** ist (nicht ein Objekt) |

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

### `documents.json` — Job-Definition (Array)

**Achtung:** `documents.json` ist ein **JSON-Array**, kein Objekt.

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|-------------|
| `outputFile` | String | Ja | Ausgabepfad (relativ zu project.dir) |
| `stereotype` | String | Ja | Modell-Stereotyp (muss in `chatModels[]` existieren) |
| `prompt` | String | Ja | Handlebars-Template-Datei (in `.dp/`) |
| `forEach` | String | Nein | Glob-Pattern für per-Datei-Jobs (z.B. `src/**/*Controller.java`) |
| `outputDir` | String | Nein | Ausgabeverzeichnis (Alternative zu outputFile) |
| `ps` | String | Nein | Post-Script (wird an das Ergebnis angehängt) |
| `filterList` | String | Nein | Post-Processing-Filter (z.B. `"noSurroundingCodeBlock"`) |
| `storePrompt` | String | Nein | Pfad zum Speichern des aufgelösten Prompts |
| `args` | Map | Nein | Kontext-Argumente für das Template (z.B. `{ "focusArea": "security" }`) |

### `chatModels[]` — Modell-Konfiguration (in application.yaml)

| Feld | Typ | Pflicht | Beschreibung |
|------|-----|---------|-------------|
| `stereoType` | String | Ja | Stereotyp-Name (z.B. `"documentation"`, `"javadoc"`) |
| `serverType` | String | Ja | Provider (`ollama`, `lm-studio`, `openai`, `gemini`, `azure`) |
| `modelName` | String | Ja | Modell-Name (z.B. `"qwen3.6-27b"`) |
| `providerUrl` | String | Nein | API-Endpoint (provider-spezifisch) |
| `apiKey` | String | Nein | API-Schlüssel (oder `${ENV_VAR}`) |
| `temperature` | Double | Nein | Temperatur (default: 0.0) |
| `timeoutSecs` | Int | Nein | Timeout in Sekunden (default: 60) |

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
