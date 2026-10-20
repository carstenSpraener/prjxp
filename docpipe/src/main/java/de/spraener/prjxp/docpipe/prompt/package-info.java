/**
 * Prompt template resolution for the DocPipe documentation pipeline.
 *
 * <p>This package provides the mechanism for transforming Handlebars-based prompt templates
 * into fully resolved prompts that are sent to a Large Language Model (LLM). It implements a
 * plugin architecture where dynamic, project-specific content is injected into templates at
 * resolution time.</p>
 *
 * <h2>Core Responsibility</h2>
 * <p>The package's primary purpose is to read prompt template files (using the Handlebars
 * templating engine), register a set of {@link TemplateResolver} implementations as
 * Handlebars helpers, and produce the final prompt string. This allows prompts to reference
 * project source code, execute scripts, include external files, and inject tool descriptions
 * without hardcoding content into the template itself.</p>
 *
 * <h2>Architecture</h2>
 * <p>The package is organized around two key abstractions and one orchestrating service:</p>
 * <ul>
 *   <li><b>{@link TemplateResolver}</b> – The central SPI (Service Provider Interface).
 *       Each implementation declares a unique ID (and optional aliases) and a
 *       {@code resolve(File baseDir, Object context, Options options)} method that returns
 *       the string to be substituted into the template. Spring auto-discovers all
 *       implementations and injects them into the service.</li>
 *   <li><b>{@link SourceSkeletonizer}</b> – A secondary SPI used by
 *       {@link SourceSkeletonResolver} to produce reduced "skeleton" representations of
 *       source files (e.g., method signatures only). Implementations declare which file
 *       extensions they support via {@code supports(File, String)}.</li>
 *   <li><b>{@link PromptResolvingService}</b> – The primary entry point. It reads the
 *       template file, wraps each {@link TemplateResolver} in an internal
 *       {@code TRHelper} adapter (bridging to Handlebars' {@code Helper<Object>} API),
 *       registers all helpers by ID and alias, compiles the template, and applies it with
 *       the task's argument map as context.</li>
 * </ul>
 *
 * <h2>Built-in Resolvers</h2>
 * <p>The following {@link TemplateResolver} implementations are provided out of the box:</p>
 * <ul>
 *   <li><b>{@link SourceDumpResolver}</b> ({@code src-dump} / {@code java-src-dump}) –
 *       Dumps full source files from a directory (optionally recursive) into Markdown
 *       code blocks.</li>
 *   <li><b>{@link SourceSkeletonResolver}</b> ({@code src-skeleton}) – Scans a directory
 *       and generates skeletonized source (signatures only) using registered
 *       {@link SourceSkeletonizer} instances; falls back to a full dump if no
 *       skeletonizer matches.</li>
 *   <li><b>{@link GroovyResolver}</b> ({@code groovy}) – Executes an inline Groovy script
 *       with access to the config directory, Handlebars options, Spring
 *       {@code ApplicationContext}, and the current context map.</li>
 *   <li><b>{@link URLResolver}</b> ({@code URL}) – Reads and inlines the content of a file
 *       referenced by a URL path relative to the configuration directory.</li>
 *   <li><b>{@link CurrentFileResolver}</b> ({@code currentFile}) – Inlines the content of
 *       the file identified in the current processing context.</li>
 *   <li><b>{@link GRResolver}</b> ({@code gr}) – Enriches a block of template text using
 *       project-specific GR (General Requirements) data via
 *       {@code GRPromptEnrichment}.</li>
 *   <li><b>{@link McpProjectResolver}</b> ({@code mcp-project} / {@code mcpProject}) –
 *       Injects MCP tool descriptions and the active project name to guide the LLM in
 *       tool-assisted information gathering.</li>
 * </ul>
 *
 * <h2>Primary Use Cases / Entry Points</h2>
 * <ul>
 *   <li><b>Resolving a prompt for a content-creation task:</b>
 *       Call {@link PromptResolvingService#resolve(de.spraener.prjxp.docpipe.content.ContentCreationTask)}
 *       to obtain the fully resolved prompt string. The service locates the template file
 *       in the project's {@code .dotpipe} configuration directory, resolves all helpers,
 *       and optionally persists the resolved prompt to disk.</li>
 *   <li><b>Extending with custom resolvers:</b> Implement {@link TemplateResolver},
 *       annotate the class with {@code @Component}, and it will be automatically
 *       registered as a Handlebars helper under its declared ID and aliases.</li>
 *   <li><b>Adding a new language skeletonizer:</b> Implement {@link SourceSkeletonizer}
 *       and register it as a Spring component. {@link SourceSkeletonResolver} will
 *       discover it and use it for matching file extensions.</li>
 * </ul>
 *
 * <h2>Error Handling</h2>
 * <p>Failures during template resolution are wrapped in {@link TemplateException}
 * (a {@link RuntimeException}), which is thrown by the internal helper adapter. Individual
 * resolvers may log warnings and provide fallback content (e.g., a full file dump when a
 * skeletonizer is unavailable) rather than failing the entire resolution.</p>
 *
 * @see TemplateResolver
 * @see PromptResolvingService
 * @see SourceSkeletonizer
 * @see TemplateException
 */
package de.spraener.prjxp.docpipe.prompt;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

