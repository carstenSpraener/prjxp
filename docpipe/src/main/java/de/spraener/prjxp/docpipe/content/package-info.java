/**
 * Provides the content-generation orchestration layer for DocPipe documentation jobs.
 *
 * <p>
 * This package is responsible for turning a configured content creation entry into an
 * output document. It coordinates prompt resolution, change detection, LLM invocation,
 * optional post-processing, and writing the generated content to its configured output
 * location.
 * </p>
 *
 * <p>
 * The central entry point is {@link ContentCreationService}. It receives a
 * {@link ContentCreationTask}, resolves the prompt for that task, delegates update
 * decisions to {@link ContentUpdateRequiredController}, invokes the configured LLM
 * service when regeneration is required, applies matching {@link ContentFilter}
 * implementations, and finally writes the result through the DocPipe output
 * infrastructure.
 * </p>
 *
 * <p>
 * The main types collaborate as follows:
 * </p>
 * <ul>
 *   <li>{@link ContentCreationTask} combines a DocPipe job with one content creation
 *       configuration and represents a single unit of work.</li>
 *   <li>{@link ContentCreationService} orchestrates the complete creation pipeline.</li>
 *   <li>{@link ContentUpdateRequiredController} avoids unnecessary regeneration by
 *       storing and comparing SHA-256 hashes of resolved prompts per output file.</li>
 *   <li>{@link ContentFilter} defines the extension point for post-processing generated
 *       text before it is written.</li>
 *   <li>{@link NoSurroundingCodeBlock} is a built-in filter that removes surrounding
 *       Markdown code fences from LLM responses.</li>
 * </ul>
 *
 * <p>
 * Typical use cases are:
 * </p>
 * <ul>
 *   <li>Generate documentation files from configured prompt templates and job context.</li>
 *   <li>Skip expensive LLM calls when the resolved prompt for an output file has not
 *       changed since the previous run.</li>
 *   <li>Post-process LLM output using named filters configured on the content creation
 *       model.</li>
 *   <li>Record generated outputs and failures during in-process DocPipe runs.</li>
 * </ul>
 *
 * <p>
 * Classes in this package are designed for use with Spring dependency injection. Custom
 * post-processing can be added by registering additional {@link ContentFilter} beans and
 * referencing their {@link ContentFilter#name()} values from the content creation
 * configuration.
 * </p>
 */
package de.spraener.prjxp.docpipe.content;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

