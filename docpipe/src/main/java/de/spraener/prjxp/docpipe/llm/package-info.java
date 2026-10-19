/**
 * LLM integration layer for the DocPipe content-creation pipeline.
 *
 * <p>This package provides the service-level abstraction for communicating with
 * Large Language Models (LLMs) in the context of documentation content creation.
 * Its primary responsibility is to resolve the appropriate LLM model configuration
 * based on a documentation <em>stereotype</em> and to execute chat interactions
 * on behalf of {@link de.spraener.prjxp.docpipe.content.ContentCreationTask}
 * instances.</p>
 *
 * <h2>Architecture</h2>
 * <p>The package contains a single Spring-managed service,
 * {@link de.spraener.prjxp.docpipe.llm.LLMService}, which orchestrates two
 * upstream collaborators from the common infrastructure layer:</p>
 * <ul>
 *   <li><b>{@link de.spraener.prjxp.common.chat.KIChatProvider}</b> –
 *       Resolves a concrete chat-model instance for a given stereotype.
 *       The stereotype is extracted from the
 *       {@link de.spraener.prjxp.docpipe.model.DPContentCreation} domain object
 *       carried inside the task.</li>
 *   <li><b>{@link de.spraener.prjxp.common.mcp.McpClientManager}</b> –
 *       Decorates the resolved chat instance with Model Context Protocol (MCP)
 *       tooling before the prompt is dispatched, enabling the model to invoke
 *       external tools during the conversation.</li>
 * </ul>
 *
 * <p>The resolution chain is: <em>stereotype → chat provider → MCP decoration →
 * chat call</em>. If no model configuration exists for the requested stereotype,
 * an {@link IllegalArgumentException} is thrown, signalling a misconfiguration
 * in the job definition.</p>
 *
 * <h2>Primary Use Case</h2>
 * <p>Callers within the DocPipe pipeline (e.g., content-creation processors)
 * invoke {@code LLMService.chat(task, prompt)} to obtain a model-generated
 * response for a specific documentation task. The service is the sole entry
 * point for LLM interaction in this module, keeping model-selection logic and
 * MCP wiring encapsulated in one place.</p>
 *
 * @see de.spraener.prjxp.docpipe.llm.LLMService
 * @see de.spraener.prjxp.common.chat.KIChatProvider
 * @see de.spraener.prjxp.common.mcp.McpClientManager
 * @see de.spraener.prjxp.docpipe.content.ContentCreationTask
 */
package de.spraener.prjxp.docpipe.llm;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

