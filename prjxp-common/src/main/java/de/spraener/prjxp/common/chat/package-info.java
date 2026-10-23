/**
 * Unified abstraction layer for interacting with multiple LLM (Large Language Model) chat providers.
 *
 * <p>This package decouples application code from the specifics of individual AI model backends by
 * introducing a common {@link KIChat} interface and a pluggable supplier architecture. It supports
 * the following provider families out of the box:
 * <ul>
 *   <li><b>Ollama</b> – local model server ({@link OllamaSupplier})</li>
 *   <li><b>Google Gemini</b> – cloud API ({@link GeminiSupplier})</li>
 *   <li><b>OpenAI-compatible</b> – any server exposing the OpenAI REST API ({@link OpenAPISupplier})</li>
 *   <li><b>Azure OpenAI</b> – Microsoft's hosted OpenAI endpoint ({@link AzureOpenAPISupplier})</li>
 *   <li><b>LM Studio</b> – local desktop model server ({@link LMStudioSupplier})</li>
 *   <li><b>Custom</b> – user-registered {@link CustomChatModel} implementations ({@link CustomChatModelSupplier})</li>
 * </ul>
 *
 * <h2>Architecture</h2>
 * <p>The package is organized around three layers of abstraction:
 * <ul>
 *   <li><b>Consumer-facing API</b> – {@link KIChat} defines the contract for a chat session
 *       (text prompting, image analysis, native tool calling). {@link KIChatModelWrapper} is the
 *       primary implementation that delegates to a LangChain4j {@code ChatModel} and handles
 *       rate-limit retries and base64 image encoding. {@link EmptyKiChat} serves as a safe no-op
 *       fallback.</li>
 *   <li><b>Factory / Resolution</b> – {@link KIChatProvider} is the top-level entry point for
 *       obtaining a {@link KIChat} by model name, stereotype, or an arbitrary predicate. It
 *       resolves environment placeholders in the configuration and delegates creation to
 *       {@link KIChatModelProvider}, which maintains a thread-safe cache of instantiated chats.</li>
 *   <li><b>Provider Strategy</b> – {@link ChatModelSupplier} is the strategy interface that each
 *       backend implements. {@link KIChatModelProvider} iterates over all registered suppliers,
 *       asking each whether it {@code canProvide} the requested {@code PrjXPChatModelReference},
 *       and then calls {@code provide} to build the underlying {@code ChatModel}. The
 *       {@link ServerTypes} enum classifies references so the correct supplier is selected.</li>
 * </ul>
 *
 * <p>A utility class, {@link BodyPropertyCoercer}, ensures that request-level body properties
 * (e.g. {@code enable_thinking}) are serialized as native JSON types rather than strings,
 * preventing misinterpretation by providers that expect booleans or numbers.
 *
 * <h2>Typical Usage</h2>
 * <p>Application code typically injects {@link KIChatProvider} and requests a chat instance:
 * <pre>{@code
 * @Autowired
 * private KIChatProvider chatProvider;
 *
 * Optional<KIChat> chat = chatProvider.getByName("my-gemini-model");
 * String answer = chat.orElseThrow().chat("Hello, world!");
 * }</pre>
 *
 * <p>For programmatic or test scenarios, {@link KIChatModelProvider} can be used directly with a
 * fully resolved {@code PrjXPChatModelReference}.
 *
 * <h2>Extensibility</h2>
 * <p>To add support for a new provider, implement {@link ChatModelSupplier} (or, for providers
 * that require custom initialization, {@link CustomChatModel}) and register it as a Spring
 * {@code @Component}. No changes to existing classes are required.
 *
 * @see KIChat
 * @see KIChatProvider
 * @see KIChatModelProvider
 * @see ChatModelSupplier
 * @see ServerTypes
 */
package de.spraener.prjxp.common.chat;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

