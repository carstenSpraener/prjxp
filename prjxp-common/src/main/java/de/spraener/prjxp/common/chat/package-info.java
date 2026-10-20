/**
 * Unified abstraction layer for interacting with multiple LLM (Large Language Model) chat providers.
 *
 * <p>This package decouples application code from the specifics of any particular AI model backend.
 * It provides a single, provider-agnostic API ({@link KIChat}) for performing text conversations,
 * image analysis, and native tool/function calling, while internally routing requests to the
 * appropriate backend implementation (Ollama, Google Gemini, OpenAI-compatible APIs, Azure OpenAI,
 * LM Studio, or fully custom models).</p>
 *
 * <h2>Architecture</h2>
 * <p>The package is organized around a layered design:</p>
 * <ul>
 *   <li><b>Consumer-facing API</b> – {@link KIChat} defines the contract for chat interactions
 *       (text chat, image analysis, tool calling). {@link KIChatModelWrapper} is the primary
 *       implementation that delegates to a LangChain4j {@code ChatModel} and handles rate-limit
 *       retries. {@link EmptyKiChat} provides a safe no-op fallback.</li>
 *   <li><b>High-level lookup</b> – {@link KIChatProvider} resolves a {@link KIChat} instance by
 *       model name, stereotype, or an arbitrary predicate, performing environment-placeholder
 *       resolution on all reference fields before delegating to the model provider.</li>
 *   <li><b>Model factory</b> – {@link KIChatModelProvider} iterates over registered
 *       {@link ChatModelSupplier} strategies to instantiate the correct {@code ChatModel} for a
 *       given {@code PrjXPChatModelReference}, caching results in a concurrent map keyed by
 *       provider URL and model name.</li>
 *   <li><b>Provider strategies</b> – Each {@link ChatModelSupplier} implementation
 *       ({@code OllamaSupplier}, {@code GeminiSupplier}, {@code OpenAPISupplier},
 *       {@code AzureOpenAPISupplier}, {@code LMStudioSupplier}, {@code CustomChatModelSupplier})
 *       checks whether it can handle a reference via {@code canProvide()} and then builds the
 *       corresponding LangChain4j model via {@code provide()}. The {@link ServerTypes} enum
 *       drives the routing decision.</li>
 *   <li><b>Custom extension point</b> – {@link CustomChatModel} extends LangChain4j's
 *       {@code ChatModel} with {@code init()} and {@code canHandle()} methods, allowing
 *       project-specific model implementations to be registered and discovered by
 *       {@code CustomChatModelSupplier}.</li>
 *   <li><b>Utility</b> – {@link BodyPropertyCoercer} normalizes string-typed request body
 *       properties (e.g., {@code "false"} → {@code Boolean.FALSE}) so that JSON payloads carry
 *       native types regardless of how the value was sourced (YAML, environment variable, etc.).</li>
 * </ul>
 *
 * <h2>Primary Use Cases</h2>
 * <ul>
 *   <li>Obtaining a ready-to-use chat instance:
 *       <pre>{@code
 *       Optional<KIChat> chat = kiChatProvider.getByName("my-model");
 *       String answer = chat.map(c -> c.chat("Hello")).orElse("");
 *       }</pre></li>
 *   <li>Selecting a model by its configured stereotype (e.g., "summarizer", "coder"):
 *       <pre>{@code
 *       Optional<KIChat> chat = kiChatProvider.getByStereotype("summarizer");
 *       }</pre></li>
 *   <li>Performing image-to-text extraction:
 *       <pre>{@code
 *       String markdown = chat.analyzeImage(bufferedImage);
 *       }</pre></li>
 *   <li>Invoking native tool/function calling (when the underlying model supports it):
 *       <pre>{@code
 *       ChatResponse resp = chat.chat(messageHistory, toolSpecs);
 *       }</pre></li>
 *   <li>Registering a project-specific model by implementing {@link CustomChatModel} and
 *       exposing it as a Spring bean; it will be picked up automatically by
 *       {@code CustomChatModelSupplier}.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <p>Model definitions are supplied through the application's {@code PrjXPConfig} (a list of
 * {@code PrjXPChatModelReference} entries). All string fields in a reference support
 * environment-variable placeholder syntax (resolved via {@code EnvPlaceholderResolver}),
 * enabling externalized secrets and per-environment overrides without code changes.</p>
 *
 * @see KIChat
 * @see KIChatProvider
 * @see KIChatModelProvider
 * @see ChatModelSupplier
 * @see ServerTypes
 */
package de.spraener.prjxp.common.chat;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

