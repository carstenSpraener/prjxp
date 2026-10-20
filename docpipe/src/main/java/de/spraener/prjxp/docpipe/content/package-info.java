/**
 * Documentation content creation and post-processing.
 *
 * <p>This package provides the core machinery for generating, caching, filtering, and
 * persisting documentation content produced by a large language model (LLM). It is the
 * execution layer of the DocPipe pipeline: given a configured task, it resolves the
 * prompt, decides whether regeneration is necessary, invokes the LLM, applies optional
 * post-processing filters, and writes the final text to the designated output file.</p>
 *
 * <h2>Architectural Overview</h2>
 * <p>The classes in this package interact as follows:</p>
 * <ul>
 *   <li><b>{@link ContentCreationService}</b> – The primary orchestrator (Spring
 *       {@code @Service}). It coordinates the full lifecycle of a content-creation
 *       request: prompt resolution, update-requirement check, LLM invocation,
 *       filter application, and output-sink writing. This is the main entry point
 *       for external callers.</li>
 *   <li><b>{@link ContentCreationTask}</b> – An immutable value object that pairs a
 *       {@code DPJob} with a {@code DPContentCreation} configuration. It serves as
 *       the unit of work passed through the pipeline.</li>
 *   <li><b>{@link ContentUpdateRequiredController}</b> – A Spring {@code @Component}
 *       that implements a hash-based invalidation strategy. It stores a SHA-256 hash
 *       of the last-used prompt per output file in a flat {@code .properties} file.
 *       If the current prompt hash differs from the stored one (or no entry exists),
 *       the supplied updater {@link Runnable} is executed, thereby avoiding redundant
 *       LLM calls.</li>
 *   <li><b>{@link ContentFilter}</b> – A strategy interface for post-processing
 *       generated text. Implementations are registered as Spring beans and applied
 *       sequentially by name, as configured in the task's filter list.</li>
 *   <li><b>{@link NoSurroundingCodeBlock}</b> – A built-in {@link ContentFilter}
 *       implementation that strips enclosing Markdown code-fence delimiters
 *       ({@code ```}) from LLM output.</li>
 * </ul>
 *
 * <h2>Typical Execution Flow</h2>
 * <ol>
 *   <li>A {@link ContentCreationTask} is assembled with its job and content-creation
 *       configuration.</li>
 *   <li>{@link ContentCreationService#createContent(ContentCreationTask)} is invoked.</li>
 *   <li>The prompt is resolved from a template via the prompt-resolving service.</li>
 *   <li>{@link ContentUpdateRequiredController#onUpdateRequired} compares the prompt
 *       hash against the stored hash; if unchanged, the LLM call is skipped entirely.</li>
 *   <li>On a cache miss, the LLM service generates the content.</li>
 *   <li>Configured {@link ContentFilter} instances are applied in order.</li>
 *   <li>The final text is written through an {@code OutputSink} to the target file,
 *       and the new hash is persisted.</li>
 * </ol>
 *
 * <h2>Primary Use Cases</h2>
 * <ul>
 *   <li>Generating or regenerating documentation files (Markdown, HTML, etc.) from
 *       LLM prompts defined in project configuration.</li>
 *   <li>Incremental builds: only re-invoking the LLM when the underlying prompt has
 *       actually changed, reducing cost and latency.</li>
 *   <li>Post-processing LLM output to remove artifacts (e.g., code fences) before
 *       the content is persisted.</li>
 * </ul>
 *
 * <h2>Dependencies</h2>
 * <p>This package depends on sibling DocPipe packages for prompt resolution
 * ({@code .prompt}), LLM interaction ({@code .llm}), output I/O ({@code .io}),
 * file-path management ({@code .config}), and domain models ({@code .model}).</p>
 */
package de.spraener.prjxp.docpipe.content;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

