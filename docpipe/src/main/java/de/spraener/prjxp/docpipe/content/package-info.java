

/**
 * <p>This package provides the core orchestration and processing logic for generating, filtering, 
 * and persisting AI-driven content within the DocPipe framework. It ensures efficient, idempotent 
 * content creation by tracking prompt changes and applying configurable post-processing transformations 
 * before writing output files.</p>
 *
 * <p><b>Architecture &amp; Class Interactions:</b></p>
 * <ul>
 *   <li>{@link ContentCreationService}: Acts as the central coordinator. It receives a 
 *       {@link ContentCreationTask}, resolves the associated prompt, and orchestrates the entire generation 
 *       pipeline including LLM invocation, filtering, and file persistence.</li>
 *   <li>{@link ContentUpdateRequiredController}: Manages conditional regeneration by computing 
 *       a SHA-256 hash of the resolved prompt and comparing it against a stored flat-file database. This 
 *       prevents unnecessary LLM calls when prompts remain unchanged.</li>
 *   <li>{@link ContentFilter}: Defines a pluggable interface for post-processing generated text. 
 *       Implementations like {@link NoSurroundingCodeBlock} are applied sequentially to transform the raw 
 *       LLM output before it is written to disk.</li>
 *   <li>{@link ContentCreationTask}: Serves as a data carrier encapsulating the job context 
 *       and configuration parameters required for a single content generation operation.</li>
 * </ul>
 *
 * <p><b>Key Entry Points &amp; Use Cases:</b></p>
 * <ul>
 *   <li>{@link ContentCreationService#createContent(ContentCreationTask)}: The primary entry 
 *       point for initiating the content generation workflow.</li>
 *   <li>{@link ContentUpdateRequiredController#onUpdateRequired(String, ContentCreationTask, Runnable)}: 
 *       Used to wrap generation logic with automatic hash-based change detection.</li>
 *   <li>{@link ContentFilter}: Extension point for developers to implement custom text 
 *       transformations that are applied after LLM generation but before file output.</li>
 * </ul>
 */
package de.spraener.prjxp.docpipe.content;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

