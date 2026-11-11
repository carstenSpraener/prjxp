# Chat

The Chat panel in the PRJXP Hub web UI lets you ask natural-language questions about indexed project source code. It combines a large-language-model (LLM) backend with optional retrieval-augmented generation (RAG) over your project's code chunks, and optional tool execution, to produce grounded answers.

The chat runs entirely in the browser against the Hub's streaming API. Messages, the selected model, the active project context, and the tool toggle are all persisted in `localStorage`, so your configuration survives page reloads.

## Selecting a Model

On page load the component calls `GlobalsService.getChatModels()` to fetch the list of LLM models registered on the Hub. Each model object exposes:

| Field | Example | Purpose |
|---|---|---|
| `stereoType` | `"default"` | Logical model identifier used in the UI and in API calls |
| `serverType` | `"ollama"` | Backend server type |
| `modelName` | `"llama3"` | Concrete model name on the server |

Selection rules:

- If a previously persisted model (from `ChatSettingsPersistenceService`) still exists in the fetched list, it is restored.
- Otherwise the **first** model in the list is selected.
- If the backend returns an empty list or the request fails, the component keeps the default value `"default"`.

The currently selected model name is displayed in the status bar at the top of the chat panel.

## Project Context & RAG Retrieval

The `projectContext` signal (type `string | null`) identifies which indexed project the RAG pipeline should search.

- **`null`** – No project is selected. The thinking indicator shows *"Verabeite…"* (Processing). The LLM answers from its own knowledge only.
- **A project name** (e.g. `"prjxp"`) – The thinking indicator shows *"RAG-Suche läuft…"* (RAG search running). The backend performs a semantic search over that project's code chunks and injects the retrieved context into the prompt before the LLM generates a response.

The project name is passed as the third argument to the streaming API call. The final assistant message returned by the backend includes a `ragContext` array containing the retrieved chunks, which the UI can display alongside the answer.

## Tool Execution

The `useTools` signal (boolean, default `false`) controls whether the LLM is allowed to invoke tools during generation.

- When **disabled**, the model produces a single text response.
- When **enabled**, the backend may emit intermediate `phase` events with `phase: "tools"` that carry a `toolCalls` array. The UI updates the thinking bubble to show which tools are being executed. The final `complete` event also includes the `toolCalls` array in the assistant message.

## Sending Messages & Streaming

When you type a question and press send, the following sequence occurs:

1. **History assembly** – The last **10** messages (excluding internal `thinking`-role messages) are collected as the conversation history.
2. **User message** – Your text is appended to the visible message list.
3. **Thinking bubble** – A temporary `thinking`-role message appears with a status label.
4. **Streaming request** – `ChatService.sendStreaming(text, modelName, projectContext, useTools, history)` is called. The response arrives as Server-Sent Events (SSE):

| SSE event | Effect in the UI |
|---|---|
| `phase` | Updates the thinking bubble text (and `toolCalls` if `phase === "tools"`). |
| `complete` | Replaces the thinking bubble with the final `assistant` message, including `ragContext` and `toolCalls`. |
| `error` | Replaces the thinking bubble with an error message (`"Error: <message>"`). |

5. **Completion** – The `isSending` flag is set back to `false`, re-enabling the input.

If the HTTP request itself fails (network error, etc.), the thinking bubble is replaced with an error message and the input is re-enabled.

The message area auto-scrolls to the bottom whenever the message list changes.

## Chat Settings

Click the **settings** (gear) icon in the chat header to open the settings dialog. The dialog lets you change three values at once:

| Setting | Type | Description |
|---|---|---|
| **Model** | `string` | Which LLM to use (from the Hub's registered models). |
| **Project context** | `string \| null` | Which indexed project to search for RAG, or `null` to disable RAG. |
| **Use tools** | `boolean` | Whether the model may call tools. |

When you close the dialog, the selected values are applied to the chat and persisted to `localStorage` via `ChatSettingsPersistenceService`. On the next page load the same values are restored automatically.

## Persistence & Clearing

- **Messages** are saved to `localStorage` through `PanelStateService` every time the message list changes. On reload, previously saved messages are restored and the view scrolls to the bottom.
- **Settings** (model, project context, tool toggle) are saved via `ChatSettingsPersistenceService` and restored on load.
- **Clear chat** – Click the **clear** button to remove all messages from the current session and from `localStorage`. The panel returns to its empty state.

_This document was generated with Doc|Pipe._

