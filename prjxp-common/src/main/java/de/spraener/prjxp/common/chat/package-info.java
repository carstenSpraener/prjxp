

/**
 * <p>Provides a unified, provider-agnostic abstraction layer for interacting with various Large Language Model (LLM) services
 * within the PrjXP ecosystem. This package leverages LangChain4j under the hood while exposing a consistent {@link KIChat}
 * interface for text-based conversations and image analysis.</p>
 * <p>The primary goal of this package is to decouple application logic from specific LLM providers. It enables dynamic model
 * instantiation, configuration, and caching based on runtime {@link de.spraener.prjxp.common.config.PrjXPChatModelReference} definitions,
 * supporting providers such as Ollama, Google Gemini, LM Studio, OpenAI-compatible APIs, and Azure OpenAI.</p>
 * <p>The package follows a Supplier/Factory pattern to manage model lifecycle and configuration:</p>
 * <ul>
 *     <li><b>{@link KIChatProvider}</b> serves as the main entry point for clients. It resolves
 *     chat instances by name, stereotype, or custom predicate.</li>
 *     <li><b>{@link KIChatModelProvider}</b> handles the actual instantiation and caching of
 *     {@link KIChat} objects. It maintains a thread-safe cache to avoid redundant model creation.</li>
 *     <li><b>{@link ChatModelSupplier}</b> defines the strategy interface for provider-specific
 *     model initialization. Multiple implementations (e.g., {@link OllamaSupplier},
 *     {@link GeminiSupplier}, {@link OpenAPISupplier}) are registered as Spring beans and queried sequentially via {@code canProvide()}.</li>
 *     <li><b>{@link KIChatModelWrapper}</b> adapts the underlying LangChain4j {@code ChatModel}
 *     to the domain-specific {@link KIChat} interface, handling prompt formatting and image encoding.</li>
 * </ul>
 * <p><b>Key Use Cases:</b></p>
 * <ul>
 *     <li><b>Dynamic Model Routing:</b> Switch between different LLM providers at runtime by simply changing the configuration reference.</li>
 *     <li><b>Unified Interaction API:</b> Use {@link KIChat#chat(String)} and
 *     {@link KIChat#analyzeImage(java.awt.image.BufferedImage)} without worrying about provider-specific SDKs.</li>
 *     <li><b>Extensibility:</b> Add new providers by implementing {@link ChatModelSupplier} or
 *     {@link CustomChatModel}, and register them as Spring components.</li>
 * </ul>
 */
package de.spraener.prjxp.common.chat;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

