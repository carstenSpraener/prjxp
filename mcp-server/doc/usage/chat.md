# Chat

The Chat panel in the PRJXP Hub web UI lets you ask natural-language questions about indexed project source code. It combines a large-language-model (LLM) backend with optional retrieval-augmented generation (RAG) so that answers are grounded in the actual code of a selected project.

The panel is a standalone Angular component (`app-chat`) that renders a scrollable message list, a text input, a settings button, and a clear-chat button. All interaction is handled client-side; the backend is contacted via a streaming (SSE) endpoint.

## Selecting a Model

The model used for generation is chosen from the list returned by the Hub's `getChatModels` endpoint. Each model entry carries three fields:

| Field | Example | Purpose |
|-------|---------|---------|
| `stereoType` | `default`, `coder` | Logical alias used in the UI and persisted in settings |
| `serverType` | `ollama` | Backend server that hosts the model |
| `modelName` | `llama3` | Concrete model identifier on that server |

On page load the component:

1. Fetches the available models from the backend.
2. Reads the previously persisted choice from `localStorage`.
3. If the persisted `stereoType` still exists in the fetched list, it is restored; otherwise the **first** model in the list is selected.

The currently selected model name is displayed in the status bar at the top of the panel (`.status-item`).

## Project Context & Retrieval

The `projectContext` signal (type `string | null`) identifies which indexed project the RAG pipeline should search.

- **No project selected (`null`):** The thinking indicator shows *"Verabeite…"* and the request is sent without a project scope. The LLM answers from its own knowledge only.
- **Project selected (e.g. `"my-project"`):** The thinking indicator shows *"RAG-Suche läuft…"*. The backend retrieves relevant code chunks for the project and includes them as `ragContext` in the final response.

The `ragContext` array is attached to the assistant message object and can be inspected in the rendered message to see which chunks informed the answer.

## Sending Messages & Streaming

When you type a question and press send, the following sequence occurs:

1. **History assembly** – The last **10** messages (excluding internal `thinking` bubbles) are collected as a `{ role, content }` array and passed to the backend.
2. **User message** – Your text is appended to the visible message list.
3. **Thinking bubble** – A temporary message with role `thinking` is inserted. Its content updates in real time as SSE *phase* events arrive (e.g. `"LLM generiert Antwort…"`, `"Tools werden ausgeführt…"`).
4. **Streaming request** – `chatService.sendStreaming(text, modelName, projectContext, useTools, history)` opens an SSE stream to the Hub backend.
5. **Completion** – On the `complete` event the thinking bubble is replaced by the final assistant message, which carries `content`, `ragContext`, and `toolCalls`.
6. **Error handling** – If the stream emits an `error` event or the HTTP request fails, the thinking bubble is replaced by an assistant message whose content begins with `Error: …`.

The `isSending` signal is `true` while the stream is open and `false` once the stream completes or errors. The input area is disabled during this period.

The message area auto-scrolls to the bottom whenever the message list changes.

## Chat Settings

Click the **settings** (gear) icon (`.btn-icon`) to open the settings dialog. The dialog lets you change three values at once:

| Setting | Type | Default | Effect |
|---------|------|---------|--------|
| Model | `string` (stereoType) | first available | Which LLM backend handles the request |
| Project context | `string \| null` | `null` | Which project's code chunks are retrieved |
| Use tools | `boolean` | `false` | Whether the LLM may invoke tool calls (e.g. code-graph lookups) |

When you close the dialog, the component:

- Updates the three signals.
- Persists the values to `localStorage` via `ChatSettingsPersistenceService`.

On the next page load the persisted values are restored automatically (subject to the model-availability check described above).

## Persistence & History

Two independent persistence mechanisms keep your session across page reloads:

- **Chat messages** – Stored in `localStorage` through `PanelStateService`. Every time the `messages` signal changes, the full array is written. On `ngOnInit` the saved array is restored (if non-empty).
- **Chat settings** – Stored in `localStorage` through `ChatSettingsPersistenceService` as a single object `{ model, projectContext, useTools }`.

Because both are `localStorage`-backed, they survive tab closes and browser restarts but are scoped to the browser profile and origin.

## Clearing the Chat

The **Clear** button (`.btn-clear`) resets the conversation:

- The `messages` signal is set to an empty array.
- The persisted copy in `PanelStateService` is cleared.
- The panel returns to its empty-state view (a prompt inviting you to ask a question).

Clearing the chat does **not** change the selected model, project context, or tool setting—those remain as configured.

_This document was generated with Doc|Pipe._

