/**
 * Provides the chat-model abstraction layer used by PrjXP to create and access Large Language
 * Model (LLM) chat clients from application configuration.
 *
 * <p>
 * The package hides provider-specific setup behind a common {@link de.spraener.prjxp.common.chat.KIChat}
 * API. It converts configured {@link de.spraener.prjxp.common.config.PrjXPChatModelReference}
 * instances into LangChain4j {@link dev.langchain4j.model.chat.ChatModel} implementations and wraps
 * them in a uniform runtime interface for text chat, image analysis, and optional native tool calling.
 * </p>
 *
 * <p>
 * The central collaboration model is supplier-based:
 * </p>
 * <ul>
 *   <li>
 *     {@link de.spraener.prjxp.common.chat.KIChatProvider} is the configuration-facing entry point.
 *     It locates configured chat models by model name, stereotype, or predicate and resolves
 *     environment placeholders before model creation.
 *   </li>
 *   <li>
 *     {@link de.spraener.prjxp.common.chat.KIChatModelProvider} selects the first matching
 *     {@link de.spraener.prjxp.common.chat.ChatModelSupplier}, creates the corresponding
 *     LangChain4j model, wraps it in {@link de.spraener.prjxp.common.chat.KIChatModelWrapper},
 *     and caches created chats by provider URL and model name.
 *   </li>
 *   <li>
 *     {@link de.spraener.prjxp.common.chat.ChatModelSupplier} implementations encapsulate
 *     provider-specific construction for supported backends such as Ollama, Google Gemini,
 *     LM Studio, OpenAI-compatible APIs, Azure OpenAI-compatible endpoints, and custom models.
 *   </li>
 *   <li>
 *     {@link de.spraener.prjxp.common.chat.CustomChatModel} and
 *     {@link de.spraener.prjxp.common.chat.CustomChatModelSupplier} provide the extension point
 *     for project-specific chat model implementations without changing the built-in supplier set.
 *   </li>
 * </ul>
 *
 * <p>
 * Supported provider identifiers are defined by {@link de.spraener.prjxp.common.chat.ServerTypes}.
 * Supplier implementations use these identifiers, together with additional model reference
 * properties such as provider URL, model name, API key, timeout, temperature, arguments, and request
 * body properties, to build the correct underlying client.
 * </p>
 *
 * <p>
 * Primary use cases include:
 * </p>
 * <ul>
 *   <li>retrieving a configured chat by model name via {@link de.spraener.prjxp.common.chat.KIChatProvider#getByName(String)};</li>
 *   <li>retrieving a chat by logical stereotype via {@link de.spraener.prjxp.common.chat.KIChatProvider#getByStereotype(String)};</li>
 *   <li>creating cached or fresh chat wrappers from a resolved model reference;</li>
 *   <li>sending simple text prompts through {@link de.spraener.prjxp.common.chat.KIChat#chat(String)};</li>
 *   <li>performing image-based text extraction through {@link de.spraener.prjxp.common.chat.KIChat#analyzeImage(java.awt.image.BufferedImage)};</li>
 *   <li>using native provider tool calling when {@link de.spraener.prjxp.common.chat.KIChat#supportsTools()} returns {@code true}.</li>
 * </ul>
 *
 * <p>
 * Utility classes such as {@link de.spraener.prjxp.common.chat.BodyPropertyCoercer} ensure that
 * request-level custom body parameters are serialized with native JSON types, even when values were
 * supplied as strings through environment variables or quoted configuration.
 * </p>
 */
package de.spraener.prjxp.common.chat;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

