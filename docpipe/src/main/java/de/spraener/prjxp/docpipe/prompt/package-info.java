

/**
 * <p>Provides a flexible templating and resolution framework for generating dynamic prompts destined for Large Language Models (LLMs). The package leverages the Handlebars templating engine to process prompt templates, allowing developers to inject project-specific data, source code, and execute custom logic directly within the template syntax.</p>
 * <p><strong>Architecture & Component Interaction</strong></p>
 * <p>The core abstraction is the {@link de.spraener.prjxp.docpipe.prompt.TemplateResolver} interface, which defines a contract for resolving dynamic content. Each implementation acts as a Handlebars helper, identified by a unique string returned via {@code getID()}.</p>
 * <p>The {@link de.spraener.prjxp.docpipe.prompt.PromptResolvingService} serves as the central orchestrator. It scans for all Spring-managed {@code TemplateResolver} beans, registers them as Handlebars helpers, and processes prompt templates by reading them from the configuration directory. During template compilation, any helper invocation triggers the corresponding resolver to inject or compute content dynamically.</p>
 * <p><strong>Available Resolvers</strong></p>
 * <ul>
 *   <li>{@link de.spraener.prjxp.docpipe.prompt.GRResolver}: Enriches prompts with project-specific data using {@code GRPromptEnrichment}.</li>
 *   <li>{@link de.spraener.prjxp.docpipe.prompt.URLResolver}: Includes file content by resolving a URL-like path relative to the configuration directory.</li>
 *   <li>{@link de.spraener.prjxp.docpipe.prompt.GroovyResolver}: Executes inline Groovy scripts for complex dynamic content generation, exposing the base directory and Spring context to the script.</li>
 *   <li>{@link de.spraener.prjxp.docpipe.prompt.CurrentFileResolver}: Injects the content of a specific file referenced in the template context.</li>
 *   <li>{@link de.spraener.prjxp.docpipe.prompt.SourceDumpResolver}: Dumps source code files (e.g., Java) from a specified directory into the prompt, supporting recursive scanning and extension filtering.</li>
 * </ul>
 * <p><strong>Usage & Entry Points</strong></p>
 * <p>The primary entry point for consumers is {@link de.spraener.prjxp.docpipe.prompt.PromptResolvingService#resolve(de.spraener.prjxp.docpipe.content.ContentCreationTask)}. This method automates the entire pipeline: locating the template file, initializing the Handlebars engine with registered resolvers, and returning the fully resolved prompt string. Errors during resolution are wrapped in {@link de.spraener.prjxp.docpipe.prompt.TemplateException}.</p>
 * <p>To extend the system, developers can create new classes implementing {@code TemplateResolver}, annotate them with Spring's {@code @Component}, and utilize the corresponding helper name directly in Handlebars templates (e.g., {@code {{groovy}} ... {{/groovy}} or {{URL "path/to/file"}}).</p>
 */
package de.spraener.prjxp.docpipe.prompt;

//_This document was generated with Doc|Pipe and qwen3.6-27b-ud-mlx_

