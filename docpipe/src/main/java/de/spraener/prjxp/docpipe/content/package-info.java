

/**
 * Provides the core orchestration and management logic for generating documentation content via LLMs.
 * <p>
 * This package is responsible for the end-to-end workflow of content creation, including prompt resolution,
 * intelligent caching to avoid redundant LLM calls, post-processing of generated text, and writing the
 * final output to designated files. It acts as a bridge between prompt templates, external LLM services,
 * and the file system.
 * </p>
 * <p>
 * The package follows a service-oriented architecture centered around the {@link ContentCreationService}.
 * The main components interact as follows:
 * </p>
 * <ul>
 *   <li><b>{@link ContentCreationService}</b>: The primary orchestrator that coordinates the entire content generation lifecycle. It resolves prompts, delegates update checks, invokes the LLM, applies filters, and handles output writing.</li>
 *   <li><b>{@link ContentUpdateRequiredController}</b>: Implements a hash-based caching mechanism. It compares the SHA-256 hash of the current prompt against previously stored hashes to determine if regeneration is necessary, significantly reducing unnecessary LLM API calls.</li>
 *   <li><b>{@link ContentFilter}</b>: A strategy interface for post-processing LLM responses. Implementations like {@link NoSurroundingCodeBlock} can clean up formatting artifacts (e.g., Markdown code fences) before the content is persisted.</li>
 *   <li><b>{@link ContentCreationTask}</b>: A lightweight data carrier that bundles a job with its specific content creation configuration, providing the necessary context for execution.</li>
 * </ul>
 * <p>
 * The primary entry point for developers is {@link ContentCreationService#createContent(ContentCreationTask)}.
 * Typical use cases include:
 * </p>
 * <ul>
 *   <li>Automating the generation of documentation files based on dynamic prompt templates.</li>
 *   <li>Incrementally updating only those documentation files whose underlying prompts have changed.</li>
 *   <li>Applying customizable text transformations to LLM outputs before they are written to disk.</li>
 * </ul>
 */
package de.spraener.prjxp.docpipe.content;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

