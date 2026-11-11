# Chat

The Chat panel in the PRJXP Hub web UI lets you ask natural-language questions about indexed project source code. It combines a large-language-model (LLM) backend with optional retrieval-augmented generation (RAG) over your project's code chunks, and optional tool execution, to produce grounded answers.

## Selecting a Model

The chat uses a configurable LLM model. On load, the component calls `getChatModels()` to fetch the list of models available on the Hub. Each model entry exposes:

| Field | Example | Purpose |
|-------|---------|---------|
| `stereoType` | `"default"` | Identifier used for selection and persistence |
| `serverType` | `"ollama"` | Backend server type |
| `modelName` | `"llama3"` | Concrete model name on the server |

Selection rules:

- If a previously saved model (from localStorage) still exists in the fetched list, it is restored automatically.
- Otherwise the **first** model in the list is selected.
- If the model list is empty or the request fails, the component keeps the current value (`"default"`).

You can change the model at any time through the **Settings** dialog (see [Chat Settings](#chat-settings)).

## Project Context and RAG Retrieval

The `projectContext` signal (a project name string, or `null`) determines whether RAG retrieval is performed before the LLM generates an answer.

- **`projectContext` is `null`** – the request is sent without a project. The thinking indicator shows *"Verabeite…"* (plain LLM generation).
- **`projectContext` is set** (e.g. `"prjxp"`) – the backend runs a semantic search over that project's indexed code chunks first. The thinking indicator shows *"RAG-Suche läuft…"* while retrieval is in progress.

The project name you select is passed as the third argument to the streaming endpoint. The final assistant message includes a `ragContext` array containing the retrieved chunks that informed the answer, so you can inspect which source-code passages the model used.

## Tools

The `useTools` boolean flag (default `false`) enables tool execution during the generation phase.

- When **disabled**, the backend skips the tool-execution phase entirely.
- When **enabled**, the backend may invoke tools (e.g. `vectorSearch`, `grep`, `readFile`, `readBySignature`, `graphNode`, etc.) against the active project before or during LLM generation.

During streaming, a `phase` event with `phase: "tools"` updates the thinking bubble to show which tools are being executed and includes the `toolCalls` array in the message. The final `complete` event also carries the full `toolCalls` list, which is rendered in the assistant message.

## Sending Messages and Streaming

When you type a question and press send, the following sequence occurs:

1. **History is assembled.** The last **10** messages (excluding internal `thinking`-role messages) are collected as `{ role, content }` pairs and sent with the request.
2. **User message is appended** to the visible conversation.
3. **A "thinking" bubble** appears with a status label (see Project Context above).
4. **The request is sent** via `sendStreaming(text, modelName, projectContext, useTools, history)`, which opens a Server-Sent Events (SSE) stream.
5. **Phase events** (`event: "phase"`) update the thinking bubble in place. Example phases:
   - `llm_generating` – "LLM generiert Antwort…"
   - `tools` – shows tool execution status and `toolCalls`
6. **Completion** (`event: "complete"`) replaces the thinking bubble with the final assistant message, including `content`, `ragContext`, and `toolCalls`.
7. **Errors** (`event: "error"` or a transport-level failure) replace the thinking bubble with an error message.

The `isSending` flag is `true` while the stream is open and `false` once the stream completes or errors. The message area auto-scrolls to the bottom whenever the message list changes.

## Chat Settings

Click the **settings** (gear) icon in the chat header to open the settings dialog. The dialog lets you configure three values:

| Setting | Type | Default |
|---------|------|---------|
| Model | `string` (a `stereoType` from the available list) | `"default"` |
| Project context | `string \| null` (a project name) | `null` |
| Use tools | `boolean` | `false` |

When you close the dialog, the selected values are applied to the chat and **persisted to localStorage** so they survive page reloads. The project context is validated against the project list when the dialog opens.

## Persistence and Clearing

- **Chat messages** are saved to localStorage (via the panel-state service) every time the message list changes. On the next page load, previously saved messages are restored and the view scrolls to the bottom.
- **Settings** (model, project context, use-tools) are persisted separately and restored on load.
- **Clear chat** (the clear button in the header) empties the message list and removes the persisted messages from localStorage. Settings are *not* cleared by this action.

_This document was generated with Doc|Pipe._

