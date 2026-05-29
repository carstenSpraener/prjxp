

/**
 * Provides services for interacting with Large Language Models (LLMs) within the documentation pipeline.
 * <p>
 * This package encapsulates the logic required to bridge documentation tasks with AI-driven content generation.
 * The core component, {@link de.spraener.prjxp.docpipe.llm.LLMService}, acts as a facade that abstracts the
 * complexity of model selection and communication. It dynamically resolves the appropriate LLM configuration
 * based on documentation stereotypes defined in {@link de.spraener.prjxp.docpipe.model.DPContentCreation}
 * and delegates the actual inference requests to a provider abstraction.
 * </p>
 * <ul>
 *   <li><b>{@link de.spraener.prjxp.docpipe.llm.LLMService}</b>: The primary Spring-managed service that coordinates LLM interactions. It receives content creation tasks and prompts, extracts the required stereotype, and routes the request.</li>
 *   <li><b>{@link de.spraener.prjxp.common.chat.KIChatProvider}</b>: An abstraction responsible for supplying configured chat model instances. The service queries this provider to obtain the correct LLM client based on the resolved stereotype.</li>
 *   <li><b>{@link de.spraener.prjxp.docpipe.content.ContentCreationTask}</b> & <b>{@link de.spraener.prjxp.docpipe.model.DPContentCreation}</b>: Domain objects that carry the context and stereotype metadata required for model resolution.</li>
 * </ul>
 * <p>
 * The main entry point for consumers is the {@code chat()} method on {@link de.spraener.prjxp.docpipe.llm.LLMService}.
 * When invoked, it automatically maps the task's stereotype to a specific LLM configuration and executes the prompt.
 * If no matching model is configured for the given stereotype, an {@code IllegalArgumentException} is thrown.
 * </p>
 */
package de.spraener.prjxp.docpipe.llm;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

