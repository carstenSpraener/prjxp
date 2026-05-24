

/**
 * <p>Core package responsible for orchestrating the generation, caching, filtering, and output of documentation content within the DocPipe framework.</p>
 * <p>This package implements an idempotent content generation pipeline that leverages Large Language Models (LLMs) to produce documentation files. It ensures efficient resource usage by tracking prompt changes via cryptographic hashing, avoiding redundant LLM calls when prompts remain unchanged.</p>
 * <p><b>Architectural Overview:</b></p>
 * <ul>
 *   <li>{@code ContentCreationService} acts as the central orchestrator and primary entry point. It coordinates the end-to-end workflow: prompt resolution, update verification, LLM invocation, content filtering, and file output.</li>
 *   <li>{@code ContentUpdateRequiredController} manages idempotency by maintaining a flat hash database (properties file). It compares the SHA-256 hash of the current prompt against stored hashes to determine if regeneration is necessary.</li>
 *   <li>{@code ContentFilter} defines a strategy interface for post-processing LLM responses. Implementations (e.g., {@code NoSurroundingCodeBlock}) clean, format, or transform raw output before persistence.</li>
 *   <li>{@code ContentCreationTask} serves as a context carrier that bundles a job definition with its specific content creation configuration, providing the necessary scope for execution.</li>
 * </ul>
 * <p><b>Key Workflow:</b></p>
 * <p>When {@code ContentCreationService.createContent()} is invoked, the system resolves the prompt template and delegates to the update controller for hash comparison. If an update is required, it triggers LLM generation, applies configured filters sequentially, appends post-scripts if specified, and writes the final content to the designated output sink. The prompt hash is then persisted for future change detection.</p>
 * <p><b>Primary Use Cases:</b></p>
 * <ul>
 *   <li>Triggering documentation generation for a specific task via {@code ContentCreationService}.</li>
 *   <li>Registering custom post-processing logic by implementing the {@code ContentFilter} interface and exposing it as a Spring bean.</li>
 *   <li>Configuring idempotent generation pipelines that automatically skip unchanged prompts to optimize LLM usage.</li>
 * </ul>
 */
package de.spraener.prjxp.docpipe.content;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

