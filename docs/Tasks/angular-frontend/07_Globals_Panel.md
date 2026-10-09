# Phase 07: Globals Panel – LLM Configuration Editor

## Overview
Frontend-Panel zur Verwaltung von LLM-Konfigurationen (`PrjXPChatModelReference`).  
Persistiert als `chat-models` Section in `application.yaml`.

## Backend (mcp-server module)

### New Controller: `/prjxp/globals`
| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/prjxp/globals/chat-models` | Liste aller Chat-Modelle (`List<PrjXPChatModelReference>`) |
| `POST` | `/prjxp/globals/chat-models` | Neues Chat-Model erstellen |
| `PUT` | `/prjxp/globals/chat-models/{name}` | Chat-Model aktualisieren |
| `DELETE` | `/prjxp/globals/chat-models/{name}` | Chat-Model löschen |
| `GET` | `/prjxp/globals/server-types` | Supported server types enum values |

### Service Layer
- `GlobalsService`: CRUD für `PrjXPChatModelReference`
- Liest/schreibt die `chat-models` Section aus/ins `application.yaml`
- Nutzt bestehende `PrjXPConfig` + YAML-Parsing (Jackson/YAML)
- Nach Update: In-memory Cache invalidieren (`KIChatModelProvider` cache clear)

### DTOs (reuse from prjxp-common)
```java
// Already exists in prjxp-common:
record PrjXPChatModelReference(
  String stereoType,    // e.g. "default", "coder"
  String serverType,    // "ollama", "openapi", "lm-studio", "gemini", "custom"
  String modelName,     // e.g. "gemma-4-31b", "llama3"
  String apiKey,        // optional (null for Ollama)
  String providerUrl,   // e.g. "http://localhost:11434"
  Integer timeoutSecs,  // default 60
  Double temperature,   // default 0.0
  Map<String, String> args  // extra args for custom providers
)

// Server types enum (from prjxp-common):
enum ServerTypes {
  OLLAMA("ollama"),
  OPEN_API("openapi"),
  LM_STUDIO("lm-studio"),
  GEMINI("gemini"),
  CUSTOM("custom")
}
```

### YAML Structure (application.yaml)
```yaml
prjxp:
  chat-models:
    - stereo-type: "default"
      server-type: "lm-studio"
      model-name: "gemma-4-31b"
      api-key: "lm-studio"
      provider-url: "http://localhost:1234"
      timeout-secs: 60
      temperature: 0.7
    - stereo-type: "coder"
      server-type: "ollama"
      model-name: "llama3"
      provider-url: "http://192.168.1.140:11434"
```

## Frontend (Angular)

### New Nav Item: "Globals"
- Icon: `icon-globals.svg` (Settings/Gear icon)
- Route: `/globals`

### GlobalsComponent (`/globals`)
```
┌─────────────────────────────────────────┐
│ Globals                                 │
├─────────────────────────────────────────┤
│ Chat Models                           + │
├─────────────────────────────────────────┤
│ ┌─────────────────────────────────────┐ │
│ │ default (LM Studio)            [✎]  │ │
│ │ gemma-4-31b @ localhost:1234       │ │
│ ├─────────────────────────────────────┤ │
│ │ coder (Ollama)                 [✎]  │ │
│ │ llama3 @ 192.168.1.140:11434      │ │
│ └─────────────────────────────────────┘ │
└─────────────────────────────────────────┘
```

### ChatModelCard Component
- Shows: stereoType, serverType (badge), modelName, providerUrl
- Actions: Edit (✎), Delete (🗑)
- Color-coded badges per server type

### ChatModelEditorDialog Component
ReactiveForm mit Feldern:
| Field | Type | Options/Validation |
|-------|------|-------------------|
| Stereo Type | text input | Required, unique name |
| Server Type | select dropdown | Ollama, OpenAI, LM Studio, Gemini, Custom |
| Model Name | text input | Required (e.g. "llama3", "gemma-4-31b") |
| Provider URL | text input | Required (URL format) |
| API Key | password input | Optional, shown only for OpenAI/Gemini/Custom |
| Timeout (secs) | number input | Default 60, min 10 |
| Temperature | range slider | 0.0 - 2.0, default 0.7 |
| Extra Args | key-value pairs | Optional, for custom providers |

Conditional UI:
- API Key field shown only for `openapi`, `gemini`, `custom` server types
- Extra Args section shown only for `custom` server type

### Services
- `GlobalsService`: HTTP calls to `/prjxp/globals/*`
- Models: `ChatModelReference.model.ts`, `ServerType.model.ts`

## Testing
- Unit tests for all components (Vitest)
- Backend integration tests for CRUD endpoints
- Form validation tests in dialog

## Files to Create/Modify
### Backend (mcp-server)
- `src/main/java/de/spraener/prjxp/mcp/rest/GlobalsController.java` (NEW)
- `src/main/java/de/spraener/prjxp/mcp/rest/GlobalsService.java` (NEW)
- `src/main/java/de/spraener/prjxp/mcp/hub/ProjectConfigFileParser.java` (MODIFY - add chat-models read/write)

### Frontend
- `src/app/features/globals/globals.component.*` (NEW x4)
- `src/app/components/chat-model-card/chat-model-card.component.*` (NEW x4)
- `src/app/components/chat-model-editor-dialog/chat-model-editor-dialog.component.*` (NEW x4)
- `src/app/services/globals.service.ts` (NEW)
- `src/app/models/chat-model-reference.model.ts` (NEW)
- `src/app/components/nav-sidebar/nav-sidebar.component.ts` (MODIFY - add Globals nav item)
- `src/app/app.routes.ts` (MODIFY - add `/globals` route)
- `public/assets/icon-globals.svg` (NEW)

## Dependencies
- Reuses `PrjXPChatModelReference` from `prjxp-common` (no new DTO needed)
- Reuses existing `ServerTypes` enum values for frontend dropdown
