# Chat

The Chat panel in the PRJXP Hub web UI lets you ask questions about indexed project source code. It combines a large-language-model (LLM) response with optional retrieval-augmented generation (RAG) over your project's code chunks, and optional tool execution. All interaction happens through the browser; no additional client-side setup is required.

## Selecting a Model

When the Chat panel opens, it queries the Hub backend for the list of available chat models via `GlobalsService.getChatModels()`. Each model entry exposes:

| Field | Example | Purpose |
|-------|---------|---------|
| `stereoType` | `default`, `coder` | Identifier used for selection |
| `serverType` | `ollama` | Backend serving the model |
| `modelName` | `llama3` | Concrete model name |

The panel applies the following selection logic on load:

1. It reads the previously persisted model name from local storage.
2. If the persisted name still exists in the backend list, it is restored.
3. Otherwise, the **first** model in the returned list is selected.

You can change the model at any time through the **Settings** dialog (see [Chat Settings](#chat-settings)). The current model name is displayed in the status bar at the top of the panel.

## Project Context and RAG Retrieval

The `projectContext` setting (a project name string, or `null`) determines whether RAG retrieval is performed before the LLM generates a response.

- **No project selected (`null`):** The thinking indicator shows *"Verabeite…"* (Processing). The request is sent to the LLM without a retrieval step.
- **Project selected (e.g. `"my-project"`):** The thinking indicator shows *"RAG-Suche läuft…"* (RAG search running). The backend retrieves relevant code chunks for the specified project and includes them as context in the prompt.

The project name is passed as the third argument to the streaming request. The final assistant message carries a `ragContext` array that lists the retrieved chunks, so you can inspect which source-code passages informed the answer.

## Sending Messages and Streaming

When you type a question and press **Send**, the following sequence occurs:

1. **User message** is appended to the visible conversation.
2. A **thinking bubble** (role `thinking`) appears with a status label.
3. The request is sent to the backend via `ChatService.sendStreaming()`, which returns a Server-Sent Events (SSE) stream.
4. The stream emits events that update the UI in real time:

| SSE event | UI effect |
|-----------|-----------|
| `phase` (e.g. `llm_generating`, `tools`) | Updates the thinking-bubble text. For the `tools` phase, any `toolCalls` data is attached to the bubble. |
| `complete` | Replaces the thinking bubble with the final **assistant** message, including `ragContext` and `toolCalls`. |
| `error` | Replaces the thinking bubble with an error message (`Error: <message>`). |

While the stream is active, the `isSending` flag is `true`, which disables the input. The flag resets to `false` when the stream completes or an error occurs.

If the network connection drops or the backend returns an HTTP error, the thinking bubble is replaced with a generic error message.

## Conversation History

Each request includes the **last 10 messages** from the current conversation as context for the LLM. The history is built as follows:

- Messages with role `thinking` are **excluded** (they are transient UI indicators, not part of the dialogue).
- The remaining messages are sliced to the most recent 10.
- Each entry is reduced to `{ role: "user" | "assistant", content: string }`.

This sliding window keeps prompts bounded while preserving enough context for multi-turn questions.

## Chat Settings

Click the **Settings** icon button (gear) in the panel header to open the settings dialog. The dialog lets you configure three values:

| Setting | Type | Default | Description |
|---------|------|---------|-------------|
| Model | `string` | First available model | Which LLM to use for responses |
| Project context | `string \| null` | `null` | Which indexed project to retrieve code chunks from |
| Use tools | `boolean` | `false` | Whether to allow the LLM to invoke tools during generation |

When you close the dialog, the selected values are:

- Applied immediately to the active chat session.
- Persisted to local storage so they survive a page reload.

On the next load, the panel restores the persisted model (if still available), project context, and tools flag automatically.

## Persistence and Clearing

The Chat panel persists two kinds of data in the browser's local storage:

- **Chat messages** – saved via `PanelStateService` every time the message list changes. On reload, previously saved messages are restored and the view scrolls to the bottom.
- **Settings** (model, project context, use tools) – saved via `ChatSettingsPersistenceService` when the settings dialog is closed.

To start fresh, click the **Clear** button (`.btn-clear`). This:

1. Empties the visible message list.
2. Removes the persisted messages from local storage.

The settings (model, project, tools) are **not** reset by clearing the chat; they remain as last configured.

_This document was generated with Doc|Pipe._

