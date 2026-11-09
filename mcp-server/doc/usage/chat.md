# Chat

The Chat panel in the PRJXP Hub web UI lets you ask natural-language questions about your project source code. It combines a large language model with retrieval-augmented generation (RAG): when a project context is selected, the backend searches the embedded code chunks for relevant passages and injects them into the prompt before the model generates its answer.

## Selecting a Model

On page load the Chat panel fetches the list of available chat models from the Hub backend via `GlobalsService.getChatModels()`. Each model entry exposes:

| Field | Example | Description |
|-------|---------|-------------|
| `stereoType` | `default`, `coder` | Logical name used for selection |
| `serverType` | `ollama` | Backend serving the model |
| `modelName` | `llama3` | Concrete model identifier |

The currently selected model is displayed in the status bar at the top of the panel. If no model is available the panel simply shows the default placeholder.

You change the active model through the **Settings** dialog (see [Chat Settings](#chat-settings)). The selection is persisted in `localStorage`, so it survives page reloads. If the previously saved model is no longer present in the backend list, the panel falls back to the first model returned by the API.

## Project Context & Retrieval

The `projectContext` signal holds the name of the project whose code chunks should be used for retrieval. Its default value is `null`, meaning no RAG context is attached.

When you send a message:

- **No project selected (`null`)** – the thinking indicator shows *"Verabeite…"* and the request is sent without a project context. The model answers from its general knowledge only.
- **Project selected (e.g. `"my-project"`)** – the thinking indicator shows *"RAG-Suche läuft…"*. The backend performs a vector search over that project's embedded chunks, retrieves the most relevant passages, and includes them as `ragContext` in the final response.

The `ragContext` array returned with the assistant message is stored on the `ChatMessage` object and can be inspected in the rendered message to see which code passages informed the answer.

## Sending Messages & Streaming

Type a question into the input field and press **Enter** (or click the send button). The following sequence occurs:

1. **User message** is appended to the conversation.
2. A **thinking bubble** (role `thinking`) appears with a status label.
3. The request is sent to the backend via `ChatService.sendStreaming()`, which returns a Server-Sent Events (SSE) stream.
4. **Phase events** update the thinking bubble in real time:
   - `phase: "tools"` – tool calls are being executed; the bubble shows the tool-call details.
   - `phase: "llm_generating"` – the model is producing the answer.
   - Any other phase – the bubble shows the phase's `message` string.
5. **Complete event** replaces the thinking bubble with the final assistant message, including `ragContext` and `toolCalls`.
6. **Error event** (or a transport-level error) replaces the thinking bubble with an error message.

While a request is in flight the `isSending` flag is `true`, which disables the input to prevent concurrent requests.

### Conversation History

Each outgoing request includes the **last 10 messages** from the current conversation (excluding `thinking`-role entries) as the `history` array. This gives the model short-term conversational context without unbounded token growth.

## Chat Settings

Click the **Settings** icon (gear button) in the panel header to open the settings dialog. The dialog lets you configure three values:

| Setting | Type | Default | Effect |
|---------|------|---------|--------|
| **Model** | string (`stereoType`) | first available model | Which LLM answers your questions |
| **Project context** | string or `null` | `null` | Which project's code chunks are retrieved for RAG |
| **Use tools** | boolean | `false` | Whether the model may invoke tool calls during generation |

When you close the dialog, the chosen values are applied immediately and persisted to `localStorage` via `ChatSettingsPersistenceService`. On the next page load the panel restores all three values (model, project context, and tool toggle) automatically.

## Conversation Persistence

Chat messages are saved to `localStorage` through `PanelStateService` every time the message list changes. This means:

- **Reload the page** – your previous conversation is restored and the scroll position jumps to the bottom.
- **Clear the chat** – click the **Clear** button to remove all messages from both the in-memory list and `localStorage`. The panel returns to its empty state.

The persistence is per-browser and per-origin; it is not shared across users or devices.

## UI Elements at a Glance

| Element | Purpose |
|---------|---------|
| **H1 "Chat"** | Panel title |
| **Status bar** (`.status-item`) | Shows the active model name |
| **Message area** | Scrollable list of user, assistant, and thinking messages; auto-scrolls to bottom on new content |
| **Empty state** (`.empty-state`) | Placeholder shown when no messages exist |
| **Input field** (`<chat-input>`) | Type and send questions |
| **Settings button** (`.btn-icon`) | Opens the settings dialog |
| **Clear button** (`.btn-clear`) | Removes all messages and resets the conversation |

_This document was generated with Doc|Pipe._

