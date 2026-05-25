

/**
 * <p>Provides dynamic prompt template resolution capabilities for the DocPipe content generation pipeline.
 * This package leverages the Handlebars templating engine to process prompt templates, enabling the injection
 * of project-specific data, external file contents, dynamic script execution, and source code context before
 * prompts are submitted to Large Language Models (LLMs).</p>
 *
 * <p>The architecture follows a strategy pattern centered around the {@link de.spraener.prjxp.docpipe.prompt.TemplateResolver} interface.
 * Each resolver acts as a custom Handlebars helper that resolves dynamic placeholders within templates. Spring-managed
 * implementations include:</p>
 * <ul>
 *   <li>{@link de.spraener.prjxp.docpipe.prompt.GRResolver}: Enriches prompts with project-specific data using a project identifier.</li>
 *   <li>{@link de.spraener.prjxp.docpipe.prompt.URLResolver}: Reads and injects the content of local or external files specified by a URL.</li>
 *   <li>{@link de.spraener.prjxp.docpipe.prompt.GroovyResolver}: Executes embedded Groovy scripts with access to the configuration directory and Spring context.</li>
 *   <li>{@link de.spraener.prjxp.docpipe.prompt.SourceDumpResolver}: Scans directories and dumps source code files into Markdown-formatted blocks for LLM context.</li>
 * </ul>
 * <p>The central orchestrator, {@link de.spraener.prjxp.docpipe.prompt.PromptResolvingService}, autowires all available {@code TemplateResolver} beans.
 * During template processing, it instantiates a Handlebars engine, registers each resolver as a helper using its unique identifier,
 * and applies the compiled template to generate the final prompt string. Errors during resolution are wrapped in {@link de.spraener.prjxp.docpipe.prompt.TemplateException}.</p>
 *
 * <p>The primary entry point for consumers is the {@link de.spraener.prjxp.docpipe.prompt.PromptResolvingService} class.
 * Developers typically invoke {@code resolve(ContentCreationTask)} to process a task's configured prompt template,
 * or {@code resolve(String, File)} for direct string-based resolution against a configuration directory.
 * To extend functionality, implement the {@code TemplateResolver} interface, annotate it with Spring's {@code @Component},
 * and define a unique helper name via {@code getID()}.</p>
 */
package de.spraener.prjxp.docpipe.prompt;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

