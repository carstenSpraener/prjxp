

/**
 * <p>Provides a unified abstraction layer for integrating and managing various Large Language Model (LLM) providers
 * within the PrjXP document pipeline. This package decouples core business logic from specific LLM implementations
 * by leveraging a factory and supplier pattern built on top of the LangChain4j framework.</p>
 *
 * <h3>Architecture Overview</h3>
 * <p>The package follows a modular, strategy-based design to support multiple LLM backends seamlessly:</p>
 * <ul>
 *   <li><b>Supplier Pattern ({@link ChatModelSupplier}):</b> Defines the contract for LLM provider implementations.
 *       Each supplier checks compatibility via {@code canProvide()} and instantiates the appropriate LangChain4j
 *       chat model via {@code provide()}.</li>
 *   <li><b>Built-in Suppliers:</b> Out-of-the-box implementations are provided for popular backends, including
 *       {@link GeminiSupplier}, {@link OllamaSupplier}, {@link LMStudioSupplier}, and {@link OpenAPISupplier}.
 *       The {@link CustomChatModelSupplier} delegates to user-defined implementations of the {@link CustomChatModel} interface.</li>
 *   <li><b>Factory Orchestration ({@link ChatModelFactory}):</b> Aggregates all registered {@code ChatModelSupplier} beans,
 *       resolves the correct supplier for a given configuration reference ({@link de.spraener.prjxp.common.config.PrjXPChatModelReference}),
 *       and caches instantiated models in a thread-safe map to optimize performance.</li>
 *   <li><b>Service Layer ({@link LLMService}):</b> Acts as the primary entry point for application code. It resolves model
 *       configurations based on task stereotypes, delegates to the factory, and executes chat prompts.</li>
 *   <li><b>Type Resolution ({@link ServerTypes}):</b> An enumeration that standardizes provider identification and routing.</li>
 * </ul>
 *
 * <h3>Key Entry Points & Usage</h3>
 * <ul>
 *   <li><b>Primary Use Case:</b> Inject {@link LLMService} into your components and call
 *       {@code chat(ContentCreationTask, String)} to send prompts to the configured LLM.</li>
 *   <li><b>Extensibility:</b> To support a new LLM provider, implement {@link ChatModelSupplier} or
 *       {@link CustomChatModel}, annotate it with Spring's {@code @Component} (or equivalent), and register it.
 *       The factory will automatically discover and utilize it.</li>
 *   <li><b>Configuration:</b> Model routing and parameters (API keys, base URLs, timeouts) are driven by
 *       {@link de.spraener.prjxp.common.config.PrjXPChatModelReference} objects and externalized application properties.</li>
 * </ul>
 */
package de.spraener.prjxp.docpipe.llm;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

