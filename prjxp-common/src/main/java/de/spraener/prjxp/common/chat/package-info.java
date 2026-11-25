/**
 * Unified abstraction layer for interacting with multiple LLM (Large Language Model) chat providers.
 *
 * <p>This package decouples application logic from the specifics of any particular AI model backend.
 * It provides a provider-agnostic chat interface backed by the LangChain4j library, supporting
 * Ollama, Google Gemini, OpenAI-compatible APIs, Azure OpenAI, LM Studio, and fully custom
 * model implementations through a pluggable supplier architecture.</p>
 *
 * <h2>Architecture</h2>
 * <p>The package is organized around a layered design:</p>
 * <ul>
 *   <li><b>Chat abstraction</b> – {@link KIChat} defines the contract for text chat, image
 *       analysis, and optional native tool calling. {@link KIChatModelWrapper} is the primary
 *       implementation, wrapping a LangChain4j {@code ChatModel} and adding rate-limit retry
 *       logic. {@link EmptyKiChat} serves as a safe no-op fallback.</li>
 *   <li><b>Model provisioning (Strategy pattern)</b> – {@link ChatModelSupplier} is the
 *       strategy interface. Concrete suppliers ({@code OllamaSupplier}, {@code GeminiSupplier},
 *       {@code OpenAPISupplier}, {@code AzureOpenAPISupplier}, {@code LMStudioSupplier},
 *       {@code CustomChatModelSupplier}) each declare which {@link ServerTypes} they handle and
 *       build the corresponding LangChain4j model instance. {@link CustomChatModel} extends
 *       LangChain4j's {@code ChatModel} for user-defined backends.</li>
 *   <li><b>Factory / caching</b> – {@link KIChatModelProvider} iterates over all registered
 *       {@code ChatModelSupplier} beans, selects the first that matches the requested
 *       {@code PrjXPChatModelReference}, wraps the resulting model in a {@code KIChatModelWrapper},
 *       and caches instances by provider-URL + model-name key.</li>
 *   <li><b>High-level lookup</b> – {@link KIChatProvider} is the primary entry point for
 *       application code. It resolves environment placeholders in configured model references
 *       and delegates creation to {@code KIChatModelProvider}. Lookups can be performed by
 *       model name, stereotype, or an arbitrary predicate.</li>
 *   <li><b>Supporting types</b> – {@link ServerTypes} enumerates the recognized backend
 *       identifiers. {@link BodyPropertyCoercer} normalizes string-typed body properties
 *       (e.g. {@code "false"} → {@code Boolean.FALSE}) so that JSON payloads carry native types.</li>
 * </ul>
 *
 * <h2>Key Entry Points</h2>
 * <ul>
 *   <li>{@link KIChatProvider#getByName(String)} – obtain a chat instance by its configured model name.</li>
 *   <li>{@link KIChatProvider#getByStereotype(String)} – obtain a chat instance by its assigned stereotype (role).</li>
 *   <li>{@link KIChatProvider#apply(java.util.function.Predicate)} – obtain a chat instance via a custom filter on model references.</li>
 *   <li>{@link KIChatModelProvider#createKIChat(de.spraener.prjxp.common.config.PrjXPChatModelReference)} – create (or retrieve from cache) a chat for a fully resolved model reference.</li>
 * </ul>
 *
 * <h2>Extending the Package</h2>
 * <p>To add support for a new LLM backend, implement {@link ChatModelSupplier} (or, for
 * non-standard models, {@link CustomChatModel}) and register it as a Spring bean. The
 * {@code KIChatModelProvider} will automatically discover it. For backends that do not map
 * cleanly to an existing {@link ServerTypes} constant, use {@code CUSTOM} and differentiate
 * via the {@code args} map on the model reference.</p>
 *
 * <h2>Configuration</h2>
 * <p>All model parameters (API keys, base URLs, model names, timeouts, temperature, body
 * properties) are sourced from {@code PrjXPChatModelReference} objects defined in the
 * application configuration. Environment-variable placeholders (e.g. {@code ${OPENAI_API_KEY}})
 * are resolved at runtime via {@code EnvPlaceholderResolver}.</p>
 */
package de.spraener.prjxp.common.chat;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

