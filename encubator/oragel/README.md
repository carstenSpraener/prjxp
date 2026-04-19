# ORAGEL 🔮
> **O**ragel **R**etrieval **A**ugmented **G**eneration **E**nrichment **L**ibrary

ORAGEL is a smart CLI tool designed to bridge the gap between your local Java source code and Large Language Models (LLMs). It analyzes your codebase, identifies relevant snippets, and constructs highly contextualized prompts that are automatically delivered to your system clipboard.

## 🚀 Features

- **Smart Code Retrieval**: Automatically finds relevant classes, methods, and dependencies based on your natural language query.
- **Context Enrichment**: Enriches prompts with actual code skeletons, dependency trees, and documentation.
- **Invisible Clipboard Integration**: Once the context is assembled, the final prompt is pushed to your system clipboard—ready for `Ctrl + V` in your favorite AI chat interface.
- **Multi-Module Support**: Seamlessly navigates through complex project structures.
- **Headless-Aware**: Automatically detects the environment and configures the AWT toolkit for clipboard access.

## 🛠 Architecture

ORAGEL is built on an event-driven architecture:

1. **PromptSource**: Captures user input (CLI scanner or automated sources).
2. **Enrichment Engine**: Utilizes `JavaRetriever` and `GldRtrvr` to scan and index the codebase.
3. **Event Bus**: Dispatches an `EnrichedPromptEvent` once the context is fully gathered.
4. **ClipboardService**: A specialized listener that handles the native OS integration to update the clipboard.

## 📋 Prerequisites

- **Java 17** or higher.
- **Desktop Environment** (for clipboard access) or a properly configured X11/Wayland system.

## 🚦 Quick Start

### Build
```bash
./gradlew clean build
```

_This README was generated with Gemini – based on code documented by Chunk-Norris._
