# Phase 08: Chat Panel – MCP-Server RAG Chat

## Overview
Chat-Interface zum Interagieren mit einem LLM, wobei prjxp als MCP-Server  
für RAG (Retrieval-Augmented Generation) und Tool-Usage zur Verfügung steht.

## Architecture

```
┌─────────────┐     WebSocket/REST      ┌──────────────┐
│   Frontend   │ ◄─────────────────────► │  mcp-server  │
│  (Angular)   │                          │  (Spring AI)  │
└─────────────┘                         ┌──────────────┐
                                        │ golden-retriever│
                                        │ (RAG Engine)   │
                                        └──────────────┘
                                              │
                                        ┌──────────────┐
                                        │ lucene-store  │
                                        │ (Embeddings)  │
                                        └──────────────┘
```

## Backend (mcp-server module)

### New Controller: `/prjxp/chat`
| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/prjxp/chat/send` | Send message, get response (REST) |
| `GET` | `/prjxp/chat/ws` | WebSocket endpoint for streaming chat |
| `POST` | `/prjxp/chat/projects` | List available projects for RAG context |
| `POST` | `/prjxp/chat/tools` | List available MCP tools |

### Chat Request/Response DTOs
```java
// Request
record ChatRequest(
  String message,           // User's input
  String chatModelName,     // Which LLM to use (stereoType from globals)
  String projectContext,    // Optional: project name for RAG enrichment
  Boolean useTools          // Optional: enable tool usage (default false)
)

// Response
record ChatResponse(
  String id,               // Unique message ID
  String role,             // "user" or "assistant"
  String content,          // Response text (streamed via WS)
  List<RagContext> ragContext,  // Retrieved chunks used for enrichment
  List<ToolCall> toolCalls      // Tools executed (if enabled)
)

record RagContext(
  String projectName,
  String chunkId,
  String snippet,         // Text excerpt from chunk
  Double score            // Relevance score
)

record ToolCall(
  String toolName,
  Map<String, Object> arguments,
  String result           // Tool execution result
)
```

### Chat Service Flow
1. **Receive message** from frontend
2. **Resolve LLM**: `KIChatProvider.getByName(chatModelName)`
3. **RAG Enrichment** (if projectContext provided):
   - Query `golden-retriever` with user message + project context
   - Get relevant chunks from `lucene-store`
   - Build enriched prompt with retrieved context
4. **Tool Usage** (if useTools=true):
   - Expose MCP tools to LLM via Spring AI tool calling
   - LLM can invoke tools (search, query, etc.)
5. **Send to LLM**: Enriched prompt → selected chat model
6. **Stream response** back via WebSocket (token-by-token)

### Integration with Existing Components
- `KIChatProvider` (prjxp-common): LLM resolution
- `GldRtrvrQuestioner` (golden-retriever): RAG enrichment
- `JavaCodeRetriever`: Chunk retrieval from lucene-store
- Spring AI MCP: Tool exposure and execution

## Frontend (Angular)

### New Nav Item: "Chat"
- Icon: `icon-chat.svg` (Chat bubble icon)
- Route: `/chat`

### ChatComponent (`/chat`)
```
┌─────────────────────────────────────────┐
│ Chat                                  [⚙]│
├─────────────────────────────────────────┤
│ ┌─────────────────────────────────────┐ │
│ │ 📊 System Status                    │ │
│ │ LLM: gemma4 (LM Studio)            │ │
│ │ Project: prjxp (optional)           │ │
│ │ Tools: Enabled                      │ │
│ └─────────────────────────────────────┘ │
│                                         │
│ ┌─────────────────────────────────────┐ │
│ │                                     │ │
│ │  User:    How does the RAG engine   │ │
│ │            work in prjxp?           │ │
│ │                                     │ │
│ │  Assistant: The RAG engine uses     │ │
│ │            golden-retriever to       │ │
│ │            search the lucene-store   │ │
│ │            for relevant chunks...    │ │
│ │                                     │ │
│ │  [RAG Context: 3 chunks from       │ │
│ │   prjxp project]                   │ │
│ └─────────────────────────────────────┘ │
│                                         │
│ ┌─────────────────────────────────────┐ │
│ │ [Message input...]          [Send]  │ │
│ └─────────────────────────────────────┘ │
└─────────────────────────────────────────┘
```

### ChatMessageComponent
- Displays a single message (user or assistant)
- Different styling for roles:
  - User: Right-aligned, cyan accent
  - Assistant: Left-aligned, magenta accent
- Shows RAG context badges (if available)
- Shows tool call results (if available)

### ChatInputComponent
- Textarea for message input
- Send button
- Enter to send (Shift+Enter for newline)

### ChatSettingsDialogComponent
| Setting | Type | Options |
|---------|------|---------|
| Chat Model | select dropdown | From globals (chat-models list) |
| Project Context | select dropdown | Available projects + "None" |
| Enable Tools | toggle switch | On/Off |

### Services
- `ChatService`: WebSocket connection + REST fallback
- Models: `ChatMessage.model.ts`, `RagContext.model.ts`, `ToolCall.model.ts`

### WebSocket Protocol
```typescript
// Client → Server
{ type: 'message', payload: ChatRequest }

// Server → Client (streaming)
{ type: 'token', payload: string }        // Each token as it's generated
{ type: 'complete', payload: ChatResponse } // Final complete response
{ type: 'error', payload: string }          // Error message
```

## Features

### RAG Enrichment
- User selects a project for context
- System retrieves relevant chunks before sending to LLM
- Response includes which chunks were used (transparency)

### Tool Usage
- LLM can call MCP tools exposed by mcp-server
- Tools available: search, query, project-list, etc.
- Tool results included in response

### Streaming
- WebSocket-based token streaming for real-time response display
- Typing animation effect while streaming

### History
- Chat history stored in component (not persisted to backend initially)
- Clear chat button to reset

## Testing
- Unit tests for all components (Vitest)
- WebSocket mock for chat service tests
- Backend integration tests for chat endpoints

## Files to Create/Modify
### Backend (mcp-server)
- `src/main/java/de/spraener/prjxp/mcp/rest/ChatController.java` (NEW)
- `src/main/java/de/spraener/prjxp/mcp/rest/ChatService.java` (NEW)
- `src/main/java/de/spraener/prjxp/mcp/rest/dto/ChatRequest.java` (NEW)
- `src/main/java/de/spraener/prjxp/mcp/rest/dto/ChatResponse.java` (NEW)
- `src/main/java/de/spraener/prjxp/mcp/rest/ws/ChatWebSocketHandler.java` (NEW)

### Frontend
- `src/app/features/chat/chat.component.*` (NEW x4)
- `src/app/components/chat-message/chat-message.component.*` (NEW x4)
- `src/app/components/chat-input/chat-input.component.*` (NEW x4)
- `src/app/components/chat-settings-dialog/chat-settings-dialog.component.*` (NEW x4)
- `src/app/services/chat.service.ts` (NEW)
- `src/app/models/chat-message.model.ts` (NEW)
- `src/app/components/nav-sidebar/nav-sidebar.component.ts` (MODIFY - add Chat nav item)
- `src/app/app.routes.ts` (MODIFY - add `/chat` route)
- `public/assets/icon-chat.svg` (NEW)

## Dependencies
- Requires Phase 07 (Globals) to be completed first (chat model selection)
- Uses existing `golden-retriever` and `lucene-store` modules
- Spring AI MCP for tool exposure
